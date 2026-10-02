package org.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.agent.constants.SignalStatus;
import org.agent.client.ExchangeClient;
import org.agent.service.dto.CryptoCurrencyDTO;
import org.agent.service.dto.TradeSignalDTO;
import org.agent.utils.DataUtils;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class SignalDetector implements Runnable {

    private static final String TIMEFRAME = "4h";

    private final CollectorService collectorService;
    private final StrategyService strategyService;
    private final ExchangeClient exchangeClient;
    private final Clock clock;

    public SignalDetector() {
        this(new CollectorService(), new StrategyService(), new ExchangeClient(), Clock.systemUTC());
    }

    public SignalDetector(CollectorService collectorService, StrategyService strategyService,
                          ExchangeClient exchangeClient, Clock clock) {
        this.collectorService = collectorService;
        this.strategyService = strategyService;
        this.exchangeClient = exchangeClient;
        this.clock = clock;
    }

    @Override
    public void run() {
        try {
            findAndSaveTradeSignals();
            log.info("All available cryptocurrencies have been checked and trade signals have been saved");
        } catch (Exception e) {
            log.error("Unexpected error while detecting trade signals", e);
        }
    }

    private void findAndSaveTradeSignals() {

        log.info("Starting trade signal detection");

        List<CryptoCurrencyDTO> cryptoCurrencies = collectorService.collectSignals();

        log.info("There are {} cryptocurrencies to examine", cryptoCurrencies.size());

        AtomicInteger numberOfSignals = new AtomicInteger();

        cryptoCurrencies.forEach(cryptoCurrencyDTO -> {
            try {
                analyzeCryptoCurrency(cryptoCurrencyDTO, numberOfSignals);
            } catch (IllegalStateException e) {
                logNoBuySignal(cryptoCurrencyDTO.getSymbol(), "Rejected: " + e.getMessage());
            } catch (Exception e) {
                log.error("Error analyzing symbol {}: {}", cryptoCurrencyDTO.getSymbol(), e.getMessage(), e);
            }
        });

        log.info("Trade signal detection finished. Saved {} signals", numberOfSignals.get());
    }

    private void analyzeCryptoCurrency(CryptoCurrencyDTO cryptoCurrencyDTO, AtomicInteger numberOfSignals) {

        String symbol = cryptoCurrencyDTO.getSymbol();
        long latestClosedCandleEndTimestamp = strategyService.getLatestClosedCandleEndTimestamp(symbol);
        long lastAnalyzedCandleEndTimestamp = DataUtils.loadLastAnalyzedCandleEndTimestamp(symbol, TIMEFRAME);

        if (latestClosedCandleEndTimestamp <= lastAnalyzedCandleEndTimestamp) {
            log.debug(
                    "Skipping symbol={}, timeframe={}, latestClosedCandleEnd={}, lastAnalyzedCandleEnd={}",
                    symbol,
                    TIMEFRAME,
                    latestClosedCandleEndTimestamp,
                    lastAnalyzedCandleEndTimestamp
            );
            return;
        }

        StrategyService.AnalysisResult analysisResult = strategyService.cryptoCurrencyAnalysisResult(symbol);
        long analyzedCandleEndTimestamp = resolveAnalyzedCandleEndTimestamp(
                analysisResult,
                latestClosedCandleEndTimestamp
        );

        if (!analysisResult.hasBuySignal()) {
            logNoBuySignal(symbol, analysisResult.reason());
            DataUtils.saveLastAnalyzedCandleEndTimestamp(symbol, TIMEFRAME, analyzedCandleEndTimestamp);
            return;
        }

        boolean saved = saveSignal(symbol, analysisResult);

        if (saved) {
            numberOfSignals.incrementAndGet();
        }

        DataUtils.saveLastAnalyzedCandleEndTimestamp(symbol, TIMEFRAME, analyzedCandleEndTimestamp);
    }

    private long resolveAnalyzedCandleEndTimestamp(StrategyService.AnalysisResult analysisResult,
                                                   long latestClosedCandleEndTimestamp) {
        if (analysisResult.candleEndTimestamp() > 0) {
            return analysisResult.candleEndTimestamp();
        }

        return latestClosedCandleEndTimestamp;
    }

    private void logSavedSignal(String symbol, long detectedAtTimestamp, StrategyService.AnalysisResult analysisResult,
                                BigDecimal actualEntryPrice, BigDecimal takeProfit) {
        log.info(
                "Buy signal saved for symbol={}, signalCandleEnd={}, detectedAt={}, referenceEntry={}, "
                        + "actualEntry={}, stopLoss={}, takeProfit={}, rsi={}, riskReward={}",
                symbol,
                analysisResult.candleEndTimestamp(),
                detectedAtTimestamp,
                analysisResult.referenceEntryPrice(),
                actualEntryPrice,
                analysisResult.stopLoss(),
                takeProfit,
                analysisResult.rsi(),
                analysisResult.riskRewardRatio()
        );
    }

    private void logNoBuySignal(String symbol, String cause) {
        log.debug("No buy signal for symbol: {}, {}", StringUtils.leftPad(symbol, 17, "_"), cause);
    }

    private boolean saveSignal(String symbol, StrategyService.AnalysisResult analysisResult) {

        validateAnalysisResult(symbol, analysisResult);

        long detectedAtTimestamp = clock.instant().getEpochSecond();
        BigDecimal actualEntryPrice = exchangeClient.fetchLatestPrice(symbol);
        BigDecimal takeProfit = calculateTakeProfitFromActualEntry(actualEntryPrice, analysisResult);

        if (takeProfit == null) {
            log.warn("Skipping signal for symbol={} because actualEntry={} is not above stopLoss={}",
                    symbol,
                    actualEntryPrice,
                    analysisResult.stopLoss()
            );
            return false;
        }

        TradeSignalDTO tradeSignal = TradeSignalDTO.builder()
                .symbol(symbol)
                .timeframe(TIMEFRAME)
                .referenceEntryPrice(analysisResult.referenceEntryPrice())
                .actualEntryPrice(actualEntryPrice)
                .stopLoss(analysisResult.stopLoss())
                .takeProfit(takeProfit)
                .rsi(analysisResult.rsi())
                .riskRewardRatio(analysisResult.riskRewardRatio())
                .signalCandleEndTimestamp(analysisResult.candleEndTimestamp())
                .detectedAtTimestamp(detectedAtTimestamp)
                .status(SignalStatus.OPEN)
                .build();

        DataUtils.saveSignal(tradeSignal);
        logSavedSignal(symbol, detectedAtTimestamp, analysisResult, actualEntryPrice, takeProfit);
        return true;
    }

    private BigDecimal calculateTakeProfitFromActualEntry(BigDecimal actualEntryPrice,
                                                          StrategyService.AnalysisResult analysisResult) {
        BigDecimal risk = actualEntryPrice.subtract(analysisResult.stopLoss());

        if (risk.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal reward = risk.multiply(BigDecimal.valueOf(analysisResult.riskRewardRatio()));
        return actualEntryPrice.add(reward);
    }

    private void validateAnalysisResult(String symbol, StrategyService.AnalysisResult analysisResult) {

        if (!analysisResult.hasBuySignal()) {
            throw new IllegalArgumentException("Cannot save a non-buy signal for symbol: " + symbol);
        }

        if (analysisResult.referenceEntryPrice() == null) {
            throw new IllegalStateException("Missing reference entry price for symbol: " + symbol);
        }

        if (analysisResult.stopLoss() == null) {
            throw new IllegalStateException("Missing stop loss for symbol: " + symbol);
        }

        if (analysisResult.takeProfit() == null) {
            throw new IllegalStateException("Missing take profit for symbol: " + symbol);
        }

        if (analysisResult.stopLoss().compareTo(analysisResult.referenceEntryPrice()) >= 0) {
            throw new IllegalStateException("Stop loss must be below entry price for symbol: " + symbol);
        }

        if (analysisResult.takeProfit().compareTo(analysisResult.referenceEntryPrice()) <= 0) {
            throw new IllegalStateException("Take profit must be above entry price for symbol: " + symbol);
        }

        if (analysisResult.riskRewardRatio() <= 0) {
            throw new IllegalStateException("Invalid risk/reward ratio for symbol: " + symbol);
        }

        if (analysisResult.candleEndTimestamp() <= 0) {
            throw new IllegalStateException("Invalid candle timestamp for symbol: " + symbol);
        }
    }
}
