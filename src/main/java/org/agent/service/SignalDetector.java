package org.agent.service;


import lombok.extern.slf4j.Slf4j;
import org.agent.service.dto.AnalysisResultDto;
import org.agent.service.dto.CryptoCurrencyDTO;
import org.agent.service.dto.TradeSignalDTO;
import org.agent.utils.DataUtils;
import org.agent.utils.TradeUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class SignalDetector implements Runnable {

    private final CollectorService collectorService = new CollectorService();
    private final StrategyService strategyService = new StrategyService();

    @Override
    public void run() {
        try {
            findAndSaveTradeSignals();
            log.info("all available cryptocurrencies have been checked out and trade signals have been saved");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private void findAndSaveTradeSignals() {
        log.info("enter to findAndSaveTradeSignals");
        List<CryptoCurrencyDTO> cryptoCurrencies = collectorService.collectSignals();
        log.info("there are {} cryptocurrencies to examine", cryptoCurrencies.size());
        AtomicInteger numberOfSignals = new AtomicInteger(0);
        cryptoCurrencies.forEach(cryptoCurrencyDTO -> {
            try {
                String cryptoCurrency = cryptoCurrencyDTO.getSymbol();
                AnalysisResultDto analysisResult = strategyService.getAnalysisResult(cryptoCurrency);
                if (analysisResult.getHasBuySignal()) {
                    log.info("cryptoCurrency: '{}' has buy signal", cryptoCurrency);
                    saveSignal(cryptoCurrencyDTO, analysisResult);
                    numberOfSignals.incrementAndGet();
                    log.info("💰 Buy signal detected for symbol: {}", cryptoCurrency);
                } else {
                    log.info("❌ No buy signal for symbol: {}", cryptoCurrency);
                }
            } catch (Exception e) {
                if (e instanceof IllegalStateException illegalStateException) {

                    log.info(illegalStateException.getMessage());
                } else {
                    log.error("error in findAndSaveTradeSignals: {}", e.getMessage());
                }
            }
        });
        log.info("saved: {} signals and exit from findAndSaveTradeSignals", numberOfSignals.get());
    }

    private void saveSignal(CryptoCurrencyDTO cryptoCurrencyDTO, AnalysisResultDto analysisResult) {
        double entry = Double.parseDouble(cryptoCurrencyDTO.getLatest());
        long timestamp = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond();
        BigDecimal riskRewardRatio = calculateRiskRewardRatio(
                BigDecimal.valueOf(entry), analysisResult.getSl(), analysisResult.getTp());
        TradeSignalDTO tradeSignalDTO = TradeSignalDTO.builder()
                .symbol(cryptoCurrencyDTO.getSymbol())
                .entryPrice(entry)
                .timestamp(timestamp)
                .dateTime(TradeUtils.formatTimestamp(timestamp))
                .stopLoss(analysisResult.getSl().doubleValue())
                .takeProfit(analysisResult.getTp().doubleValue())
                .result("riskRewardRatio: " + riskRewardRatio)
                .build();
        DataUtils.saveSignal(tradeSignalDTO);
    }

    private BigDecimal calculateRiskRewardRatio(BigDecimal entryPrice, BigDecimal stopLoss, BigDecimal takeProfit) {
        if (entryPrice == null || stopLoss == null || takeProfit == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal risk = entryPrice.subtract(stopLoss);
        BigDecimal reward = takeProfit.subtract(entryPrice);

        if (risk.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        return reward.divide(risk, 2, RoundingMode.HALF_UP);
    }

}
