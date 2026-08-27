package org.agent.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.agent.client.ExchangeClient;
import org.agent.service.dto.CandleDTO;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Indicator;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class StrategyService {

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StrategyConfig {

        // MACD
        private int macdFast = 12;
        private int macdSlow = 26;
        private int macdSignal = 9;

        // Trend
        private int emaPullback = 20;
        private int emaShort = 50;
        private int emaLong = 200;

        // RSI
        private int rsiPeriod = 14;
        private double rsiMin = 50.0;
        private double rsiMax = 68.0;

        // ATR
        private int atrPeriod = 14;

        // Volume
        private int volumeSmaPeriod = 20;
        private double minimumVolumeRatio = 1.20;

        // Signal timing
        private int macdCrossLookback = 3;
        private int emaSlopeLookback = 3;
        private int pullbackLookback = 3;

        // Entry quality
        private double maxDistanceFromEma20Atr = 0.90;
        private double pullbackAtrTolerance = 0.25;
        private double maxCandleRangeAtr = 1.75;

        // Stop loss
        private int swingLowLookback = 10;
        private double stopAtrBuffer = 0.20;
        private double maxStopDistancePercent = 5.0;

        // Reward / resistance
        private int resistanceLookback = 30;
        private double targetRiskRewardRatio = 1.80;

        // Data
        private int barSeriesSize = 600;
        private int minimumBars = 300;
        private int hourlyInterval = 4;

        // Small safety margin before considering a candle closed
        private long candleCloseGraceSeconds = 5;
    }

    public record AnalysisResult(
            boolean hasBuySignal,
            BigDecimal referenceEntryPrice,
            BigDecimal takeProfit,
            BigDecimal stopLoss,
            double rsi,
            double riskRewardRatio,
            long candleEndTimestamp,
            String reason
    ) {

        public static AnalysisResult noSignal(String reason) {
            return new AnalysisResult(false, null, null, null, 0.0, 0.0, 0L, reason);
        }

        public static AnalysisResult noSignal(String reason, double rsi, long candleEndTimestamp) {
            return new AnalysisResult(false, null, null, null, rsi, 0.0, candleEndTimestamp, reason);
        }
    }

    private final ExchangeClient exchangeClient = new ExchangeClient();
    private final StrategyConfig config = new StrategyConfig();

    public AnalysisResult cryptoCurrencyAnalysisResult(String symbol) {
        BarSeries series = getHourlyBarSeries(symbol, config.getHourlyInterval(), config.getBarSeriesSize());

        if (series.getBarCount() < config.getMinimumBars()) {
            throw new IllegalStateException(
                    "Insufficient CLOSED candle data for symbol: " + symbol + ", bars=" + series.getBarCount());
        }

        return analyzeSeries(series);
    }

    private AnalysisResult analyzeSeries(BarSeries series) {

        String symbol = series.getName();
        int index = series.getEndIndex();
        Bar signalBar = series.getBar(index);

        ClosePriceIndicator close = new ClosePriceIndicator(series);
        EMAIndicator ema20 = new EMAIndicator(close, config.getEmaPullback());
        EMAIndicator ema50 = new EMAIndicator(close, config.getEmaShort());
        EMAIndicator ema200 = new EMAIndicator(close, config.getEmaLong());

        RSIIndicator rsi = new RSIIndicator(close, config.getRsiPeriod());
        ATRIndicator atr = new ATRIndicator(series, config.getAtrPeriod());

        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator volumeSma = new SMAIndicator(volume, config.getVolumeSmaPeriod());

        MACDIndicator macd = new MACDIndicator(close, config.getMacdFast(), config.getMacdSlow());
        EMAIndicator macdSignal = new EMAIndicator(macd, config.getMacdSignal());

        int requiredLookback = Math.max(
                config.getEmaLong(),
                Math.max(config.getSwingLowLookback(), config.getResistanceLookback())
        );

        if (index - series.getBeginIndex() < requiredLookback) {
            return AnalysisResult.noSignal("Indicators/lookbacks are not stable yet");
        }

        Num currentClose = close.getValue(index);
        Num currentEma20 = ema20.getValue(index);
        Num currentEma50 = ema50.getValue(index);
        Num currentEma200 = ema200.getValue(index);
        Num currentAtr = atr.getValue(index);

        double currentRsi = rsi.getValue(index).doubleValue();
        long candleEndTimestamp = signalBar.getEndTime().getEpochSecond();

        /*
         * 1. MARKET REGIME
         *
         * We want an established bullish market:
         *
         * EMA50 > EMA200
         * price > EMA50
         * EMA50 rising
         */
        boolean emaStructureBullish = currentEma50.isGreaterThan(currentEma200)
                && currentClose.isGreaterThan(currentEma50);

        int slopeIndex = index - config.getEmaSlopeLookback();
        boolean ema50Rising = currentEma50.isGreaterThan(ema50.getValue(slopeIndex));

        if (!emaStructureBullish) {
            return AnalysisResult.noSignal("Rejected: bullish EMA structure is missing", currentRsi, candleEndTimestamp);
        }

        if (!ema50Rising) {
            return AnalysisResult.noSignal("Rejected: EMA50 is not rising", currentRsi, candleEndTimestamp);
        }

        /*
         * 2. RSI
         *
         * This is a trend-following strategy, therefore RSI < 30 is not used as the buy condition.
         * We want positive momentum without buying extremely extended conditions.
         */
        boolean validRsi = currentRsi >= config.getRsiMin() && currentRsi <= config.getRsiMax();

        if (!validRsi) {
            return AnalysisResult.noSignal(
                    "Rejected: RSI outside desired range: " + currentRsi, currentRsi, candleEndTimestamp);
        }

        /*
         * 3. PRICE EXTENSION
         *
         * Price should be above EMA20, but not excessively above it.
         */
        if (!currentClose.isGreaterThan(currentEma20)) {
            return AnalysisResult.noSignal("Rejected: price is below EMA20", currentRsi, candleEndTimestamp);
        }

        Num distanceFromEma20 = currentClose.minus(currentEma20);
        Num maximumAllowedDistance = currentAtr.multipliedBy(
                series.numFactory().numOf(config.getMaxDistanceFromEma20Atr()));

        if (distanceFromEma20.isGreaterThan(maximumAllowedDistance)) {
            return AnalysisResult.noSignal(
                    "Rejected: price is too extended from EMA20", currentRsi, candleEndTimestamp);
        }

        /*
         * 4. RECENT PULLBACK
         */
        boolean hasRecentPullback = hasRecentPullbackToEma(
                series,
                ema20,
                atr,
                index,
                config.getPullbackLookback(),
                config.getPullbackAtrTolerance()
        );

        if (!hasRecentPullback) {
            return AnalysisResult.noSignal(
                    "Rejected: no recent pullback toward EMA20", currentRsi, candleEndTimestamp);
        }

        /*
         * 5. REJECT LARGE PUMP CANDLE
         */
        Num candleRange = signalBar.getHighPrice().minus(signalBar.getLowPrice());
        Num maximumCandleRange = currentAtr.multipliedBy(
                series.numFactory().numOf(config.getMaxCandleRangeAtr()));

        if (candleRange.isGreaterThan(maximumCandleRange)) {
            return AnalysisResult.noSignal(
                    "Rejected: trigger candle is too large relative to ATR", currentRsi, candleEndTimestamp);
        }

        /*
         * 6. MACD MOMENTUM RESTART
         *
         * MACD crossover may have occurred during one of the previous few candles.
         * We require MACD to remain bullish now.
         */
        boolean macdCurrentlyBullish = macd.getValue(index).isGreaterThan(macdSignal.getValue(index));
        boolean recentMacdCross = crossedUpWithin(macd, macdSignal, index, config.getMacdCrossLookback());

        if (!macdCurrentlyBullish || !recentMacdCross) {
            return AnalysisResult.noSignal(
                    "Rejected: no recent bullish MACD crossover", currentRsi, candleEndTimestamp);
        }

        /*
         * 7. PRICE CONFIRMATION
         */
        Bar previousBar = series.getBar(index - 1);

        boolean bullishCandle = signalBar.getClosePrice().isGreaterThan(signalBar.getOpenPrice());
        boolean closesAbovePreviousClose = signalBar.getClosePrice().isGreaterThan(previousBar.getClosePrice());

        if (!bullishCandle || !closesAbovePreviousClose) {
            return AnalysisResult.noSignal(
                    "Rejected: no bullish price confirmation", currentRsi, candleEndTimestamp);
        }

        /*
         * 8. VOLUME CONFIRMATION
         *
         * Use the previous bar's SMA value so the signal candle's own volume does not artificially
         * increase the average against which it is compared.
         */
        Num previousAverageVolume = volumeSma.getValue(index - 1);

        if (previousAverageVolume.isZero()) {
            return AnalysisResult.noSignal("Rejected: average volume is zero", currentRsi, candleEndTimestamp);
        }

        double volumeRatio = volume.getValue(index).dividedBy(previousAverageVolume).doubleValue();

        if (volumeRatio < config.getMinimumVolumeRatio()) {
            return AnalysisResult.noSignal(
                    "Rejected: insufficient volume ratio: " + volumeRatio, currentRsi, candleEndTimestamp);
        }

        /*
         * 9. STRUCTURAL STOP LOSS
         *
         * Stop loss is based on a recent swing low instead of using:
         *
         * entry - fixed ATR multiplier
         */
        Num recentSwingLow = findLowestLow(series, index - config.getSwingLowLookback() + 1, index);
        Num atrBuffer = currentAtr.multipliedBy(series.numFactory().numOf(config.getStopAtrBuffer()));
        Num stopLossNum = recentSwingLow.minus(atrBuffer);

        if (!stopLossNum.isPositive()) {
            return AnalysisResult.noSignal("Rejected: invalid stop loss", currentRsi, candleEndTimestamp);
        }

        Num riskNum = currentClose.minus(stopLossNum);

        if (!riskNum.isPositive()) {
            return AnalysisResult.noSignal("Rejected: invalid trade risk", currentRsi, candleEndTimestamp);
        }

        double stopDistancePercent = riskNum
                .dividedBy(currentClose)
                .multipliedBy(series.numFactory().numOf(100))
                .doubleValue();

        if (stopDistancePercent > config.getMaxStopDistancePercent()) {
            return AnalysisResult.noSignal(
                    "Rejected: stop is too far away: " + stopDistancePercent + "%",
                    currentRsi,
                    candleEndTimestamp
            );
        }

        /*
         * 10. TAKE PROFIT
         *
         * Risk comes from market structure.
         * Reward is then calculated from that real risk.
         */
        Num rewardNum = riskNum.multipliedBy(series.numFactory().numOf(config.getTargetRiskRewardRatio()));
        Num takeProfitNum = currentClose.plus(rewardNum);

        /*
         * 11. RESISTANCE
         *
         * Reject a trade if recent resistance exists above entry but below the intended TP.
         */
        Num recentResistance = findHighestHigh(
                series,
                index - config.getResistanceLookback(),
                index - 1
        );

        boolean resistanceAboveEntry = recentResistance.isGreaterThan(currentClose);
        boolean resistanceBeforeTarget = recentResistance.isLessThan(takeProfitNum);

        if (resistanceAboveEntry && resistanceBeforeTarget) {
            return AnalysisResult.noSignal(
                    "Rejected: recent resistance blocks take-profit target", currentRsi, candleEndTimestamp);
        }

        /*
         * 12. FINAL SIGNAL
         */
        BigDecimal entry = currentClose.bigDecimalValue();
        BigDecimal stopLoss = stopLossNum.bigDecimalValue();
        BigDecimal takeProfit = takeProfitNum.bigDecimalValue();

        BigDecimal risk = entry.subtract(stopLoss);
        BigDecimal reward = takeProfit.subtract(entry);

        double riskRewardRatio = reward.divide(risk, 8, RoundingMode.HALF_UP).doubleValue();

        log.info(
                "BUY SIGNAL symbol={}, candleEnd={}, entry={}, stopLoss={}, takeProfit={}, rsi={}, volumeRatio={}, "
                        + "stopDistance={}%, riskReward={}",
                symbol,
                signalBar.getEndTime(),
                entry,
                stopLoss,
                takeProfit,
                currentRsi,
                volumeRatio,
                stopDistancePercent,
                riskRewardRatio
        );

        return new AnalysisResult(
                true,
                entry,
                takeProfit,
                stopLoss,
                currentRsi,
                riskRewardRatio,
                candleEndTimestamp,
                "Trend + pullback + momentum + volume confirmed"
        );
    }

    private BarSeries getHourlyBarSeries(String symbol, int hourlyInterval, int size) {

        List<CandleDTO> candles = exchangeClient.fetchHourlyClosingPrices(symbol, hourlyInterval, size);

        if (candles == null || candles.isEmpty()) {
            throw new IllegalStateException("No candles returned for symbol: " + symbol);
        }

        /*
         * Do not trust exchange ordering.
         *
         * TreeMap provides chronological ordering and also removes candles with duplicate timestamps.
         */
        Map<Long, CandleDTO> sortedCandles = candles.stream()
                .filter(this::isValidCandle)
                .collect(Collectors.toMap(
                        CandleDTO::getTimestamp,
                        Function.identity(),
                        (oldValue, newValue) -> newValue,
                        TreeMap::new
                ));

        BarSeries series = new BaseBarSeriesBuilder().withName(symbol).build();

        Duration interval = Duration.ofHours(hourlyInterval);
        Instant now = Instant.now();

        for (CandleDTO candle : sortedCandles.values()) {

            /*
             * Assumption:
             * LBank timestamp represents candle BEGIN time.
             *
             * Example for 4h:
             *
             * timestamp = 08:00
             * candleEnd = 12:00
             */
            Instant candleBeginTime = Instant.ofEpochSecond(candle.getTimestamp());
            Instant candleEndTime = candleBeginTime.plus(interval);

            /*
             * Ignore the currently forming candle.
             */
            Instant safeCloseTime = candleEndTime.plusSeconds(config.getCandleCloseGraceSeconds());

            if (safeCloseTime.isAfter(now)) {
                continue;
            }

            series.barBuilder()
                    .timePeriod(interval)
                    .endTime(candleEndTime)
                    .openPrice(candle.getOpen())
                    .highPrice(candle.getHigh())
                    .lowPrice(candle.getLow())
                    .closePrice(candle.getClose())
                    .volume(candle.getVolume())
                    .add();
        }

        return series;
    }

    private boolean hasRecentPullbackToEma(BarSeries series, EMAIndicator ema, ATRIndicator atr, int currentIndex,
                                           int lookback, double atrTolerance) {

        int start = Math.max(series.getBeginIndex(), currentIndex - lookback + 1);

        for (int i = start; i <= currentIndex; i++) {
            Num emaValue = ema.getValue(i);
            Num tolerance = atr.getValue(i).multipliedBy(series.numFactory().numOf(atrTolerance));
            Num maximumTouchPrice = emaValue.plus(tolerance);

            Num low = series.getBar(i).getLowPrice();
            Num close = series.getBar(i).getClosePrice();

            boolean touchedEmaArea = !low.isGreaterThan(maximumTouchPrice);
            boolean closedAboveEma = close.isGreaterThan(emaValue);

            if (touchedEmaArea && closedAboveEma) {
                return true;
            }
        }

        return false;
    }

    private boolean crossedUpWithin(Indicator<Num> first, Indicator<Num> second, int currentIndex, int lookback) {

        int start = Math.max(seriesBeginIndex(first), currentIndex - lookback + 1);

        for (int i = start; i <= currentIndex; i++) {
            Num previousFirst = first.getValue(i - 1);
            Num previousSecond = second.getValue(i - 1);
            Num currentFirst = first.getValue(i);
            Num currentSecond = second.getValue(i);

            boolean previouslyBelowOrEqual = !previousFirst.isGreaterThan(previousSecond);
            boolean currentlyAbove = currentFirst.isGreaterThan(currentSecond);

            if (previouslyBelowOrEqual && currentlyAbove) {
                return true;
            }
        }

        return false;
    }

    /*
     * Indicators do not expose the series begin index uniformly across TA4J versions.
     * Since this method always runs after sufficient warm-up, index 1 is safe for this use case.
     */
    private int seriesBeginIndex(Indicator<Num> indicator) {
        return 1;
    }

    private Num findLowestLow(BarSeries series, int startIndex, int endIndex) {

        int start = Math.max(series.getBeginIndex(), startIndex);
        Num lowest = series.getBar(start).getLowPrice();

        for (int i = start + 1; i <= endIndex; i++) {
            Num low = series.getBar(i).getLowPrice();

            if (low.isLessThan(lowest)) {
                lowest = low;
            }
        }

        return lowest;
    }

    private Num findHighestHigh(BarSeries series, int startIndex, int endIndex) {

        int start = Math.max(series.getBeginIndex(), startIndex);
        Num highest = series.getBar(start).getHighPrice();

        for (int i = start + 1; i <= endIndex; i++) {
            Num high = series.getBar(i).getHighPrice();

            if (high.isGreaterThan(highest)) {
                highest = high;
            }
        }

        return highest;
    }

    private boolean isValidCandle(CandleDTO candle) {

        if (candle == null || candle.getTimestamp() <= 0) {
            return false;
        }

        double open = candle.getOpen();
        double high = candle.getHigh();
        double low = candle.getLow();
        double close = candle.getClose();
        double volume = candle.getVolume();

        if (!Double.isFinite(open)
                || !Double.isFinite(high)
                || !Double.isFinite(low)
                || !Double.isFinite(close)
                || !Double.isFinite(volume)) {
            return false;
        }

        if (open <= 0 || high <= 0 || low <= 0 || close <= 0 || volume < 0) {
            return false;
        }

        if (high < Math.max(open, close)) {
            return false;
        }

        if (low > Math.min(open, close)) {
            return false;
        }

        return high >= low;
    }
}