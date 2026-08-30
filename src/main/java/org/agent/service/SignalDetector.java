package org.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.agent.constants.SignalStatus;
import org.agent.service.dto.CryptoCurrencyDTO;
import org.agent.service.dto.TradeSignalDTO;
import org.agent.utils.DataUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class SignalDetector implements Runnable {

    private static final String TIMEFRAME = "4h";

    private final CollectorService collectorService = new CollectorService();
    private final StrategyService strategyService = new StrategyService();

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

        StrategyService.AnalysisResult analysisResult = strategyService.cryptoCurrencyAnalysisResult(symbol);

        if (!analysisResult.hasBuySignal()) {
            logNoBuySignal(symbol, analysisResult.reason());
            return;
        }

        saveSignal(symbol, analysisResult);
        numberOfSignals.incrementAndGet();

        log.info(
                "Buy signal saved for symbol={}, entry={}, stopLoss={}, takeProfit={}, rsi={}, riskReward={}",
                symbol,
                analysisResult.referenceEntryPrice(),
                analysisResult.stopLoss(),
                analysisResult.takeProfit(),
                analysisResult.rsi(),
                analysisResult.riskRewardRatio()
        );
    }

    private void logNoBuySignal(String symbol, String cause) {
        log.debug("No buy signal for symbol: {}, {}", StringUtils.leftPad(symbol, 17, "_"), cause);
    }

    private void saveSignal(String symbol, StrategyService.AnalysisResult analysisResult) {

        validateAnalysisResult(symbol, analysisResult);

        TradeSignalDTO tradeSignal = TradeSignalDTO.builder()
                .symbol(symbol)
                .timeframe(TIMEFRAME)
                .entryPrice(analysisResult.referenceEntryPrice())
                .stopLoss(analysisResult.stopLoss())
                .takeProfit(analysisResult.takeProfit())
                .rsi(analysisResult.rsi())
                .riskRewardRatio(analysisResult.riskRewardRatio())
                .timestamp(analysisResult.candleEndTimestamp())
                .status(SignalStatus.OPEN)
                .build();

        DataUtils.saveSignal(tradeSignal);
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