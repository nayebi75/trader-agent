package org.agent.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.agent.client.ExchangeClient;
import org.agent.service.dto.AnalysisResultDto;
import org.agent.service.dto.CandleDTO;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
public class StrategyService {

    public AnalysisResultDto getAnalysisResult(String cryptoCurrency) {
        return getAnalysisResultFromCryptoCurrency(cryptoCurrency);
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StrategyConfig {
        private int macdFast = 12;
        private int macdSlow = 26;
        private int macdSignal = 9;
        private int emaShort = 50;
        private int emaLong = 200;
        private int rsiPeriod = 14;
        private int atrPeriod = 14;
        private int volumeSmaPeriod = 20;
        private double atrStopMultiplier = 1.5;
        private double atrTakeProfitMultiplier = 3.0;
        private double rsiOversold = 30.0;
        private double rsiOverbought = 70.0;
        private double minRiskRewardRatio = 1.5;
        private int barSeriesSize = 250;
        private int hourlyInterval = 4;
    }

    private final ExchangeClient exchangeClient = new ExchangeClient();
    private final StrategyConfig config = new StrategyConfig();

    @SuppressWarnings("all")
    private AnalysisResultDto analyzeSeries(BarSeries series) {

        AnalysisResultDto analysisResult = new AnalysisResultDto(false, null, null);
        String symbol = series.getName();

        int lastIndex = series.getEndIndex();
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 1. Trend filter: Price above long-term EMA
        EMAIndicator emaLong = new EMAIndicator(closePrice, config.getEmaLong());
        boolean isAboveEmaLong = closePrice.getValue(lastIndex)
                .isGreaterThan(emaLong.getValue(lastIndex));
        log.info("symbol: {} isAboveEmaLong: {}", symbol, isAboveEmaLong);

        // 2. MACD bullish crossover
        MACDIndicator macd = new MACDIndicator(closePrice, config.getMacdFast(), config.getMacdSlow());
        EMAIndicator macdSignal = new EMAIndicator(macd, config.getMacdSignal());
        boolean macdCrossover;
        boolean macdBullish = macd.getValue(lastIndex)
                .isGreaterThan(macdSignal.getValue(lastIndex));

        boolean macdCrossUp =
                macd.getValue(lastIndex - 1).isLessThan(macdSignal.getValue(lastIndex - 1))
                        && macd.getValue(lastIndex).isGreaterThan(macdSignal.getValue(lastIndex));

        macdCrossover = macdBullish & macdCrossUp;
        log.info("symbol: {} macdCrossover: {}", symbol, macdCrossover);

        // 3. RSI oversold condition
        RSIIndicator rsi = new RSIIndicator(closePrice, config.getRsiPeriod());
        boolean isOversold = rsi.getValue(lastIndex)
                .isLessThan(series.numFactory().numOf(config.getRsiOversold()));
        log.info("symbol: {} isOversold: {}", symbol, isOversold);

        // 4. Volume confirmation (optional but recommended)
        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator volumeSMA = new SMAIndicator(volume, config.getVolumeSmaPeriod());
        boolean highVolume = volume.getValue(lastIndex)
                .isGreaterThan(volumeSMA.getValue(lastIndex));
        log.info("symbol: {} highVolume: {}", symbol, highVolume);

        // Combine signals (volume is optional - can remove if too strict)
        boolean hasBuySignal = isAboveEmaLong && macdCrossover && highVolume;// && isOversold;


        // Calculate risk levels if signal exists
        if (hasBuySignal) {
            ATRIndicator atr = new ATRIndicator(series, config.getAtrPeriod());
            int lastClosedIndex = series.getEndIndex();
            Num currentPrice = series.getBar(lastClosedIndex).getClosePrice();
            Num atrValue = atr.getValue(lastClosedIndex);

            BigDecimal entryPrice = currentPrice.bigDecimalValue().setScale(4, RoundingMode.HALF_UP);

            BigDecimal stopLoss = currentPrice
                    .minus(atrValue.multipliedBy(series.numFactory().numOf(config.getAtrStopMultiplier())))
                    .bigDecimalValue().setScale(4, RoundingMode.HALF_UP);

            BigDecimal takeProfit = currentPrice
                    .plus(atrValue.multipliedBy(series.numFactory().numOf(config.getAtrTakeProfitMultiplier())))
                    .bigDecimalValue().setScale(4, RoundingMode.HALF_UP);

            boolean riskRewardIsValidated = validateRiskReward(entryPrice, stopLoss, takeProfit);
            if (riskRewardIsValidated)
                analysisResult = new AnalysisResultDto(true, takeProfit, stopLoss);
        }
        return analysisResult;
    }

    private BarSeries getHourlyBarSeries(String symbol, int hourlyInterval, int size) {
        List<CandleDTO> candles = exchangeClient.fetchHourlyClosingPrices(symbol, hourlyInterval, size);
        BarSeries series = new BaseBarSeriesBuilder().withName(symbol).build();
        for (CandleDTO c : candles) {
            Bar bar = new BaseBar(
                    Duration.ofHours(hourlyInterval),
                    Instant.ofEpochSecond(c.getTimestamp()).atZone(ZoneOffset.UTC).toInstant(),
                    series.numFactory().numOf(c.getOpen()),
                    series.numFactory().numOf(c.getHigh()),
                    series.numFactory().numOf(c.getLow()),
                    series.numFactory().numOf(c.getClose()),
                    series.numFactory().numOf(c.getVolume()),
                    null,
                    0L
            );
            series.addBar(bar);
        }
        return series;
    }

    private AnalysisResultDto getAnalysisResultFromCryptoCurrency(String symbol) {
        BarSeries series = getHourlyBarSeries(symbol, config.getHourlyInterval(), config.getBarSeriesSize());
        if (series.getBarCount() < Math.max(config.getEmaLong(), config.getBarSeriesSize())) {
            throw new IllegalStateException("❌ Insufficient data for symbol: " + symbol);
        } else {
            return analyzeSeries(series);
        }
    }

    private boolean validateRiskReward(BigDecimal entryPrice, BigDecimal stopLoss, BigDecimal takeProfit) {
        if (entryPrice == null || stopLoss == null || takeProfit == null) {
            return false;
        }

        BigDecimal risk = entryPrice.subtract(stopLoss);
        BigDecimal reward = takeProfit.subtract(entryPrice);

        // Avoid division by zero or negative values
        if (risk.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        BigDecimal rrRatio = reward.divide(risk, 2, RoundingMode.HALF_UP);
        return rrRatio.compareTo(BigDecimal.valueOf(config.getMinRiskRewardRatio())) >= 0;
    }

}
