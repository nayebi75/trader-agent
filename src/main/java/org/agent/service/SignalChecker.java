package org.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.agent.client.ExchangeClient;
import org.agent.constants.SignalStatus;
import org.agent.service.dto.CandleDTO;
import org.agent.service.dto.TradeSignalDTO;
import org.agent.utils.DataUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
public class SignalChecker implements Runnable {

    private static final int HOURLY_INTERVAL = 1;
    private static final int HOURLY_BATCH_SIZE = 250;
    private static final int MINUTES_PER_HOUR = 60;
    private static final long SECONDS_PER_HOUR = 60 * 60;

    private final ExchangeClient exchangeClient = new ExchangeClient();

    @Override
    public void run() {
        log.info("Starting signal checker");

        try {
            validateSignals();
        } catch (Exception e) {
            log.error("Unexpected error while checking trade signals", e);
        }

        log.info("Signal checker finished");
    }

    public void validateSignals() {

        List<TradeSignalDTO> signals = DataUtils.loadTradeSignals();

        if (signals == null || signals.isEmpty()) {
            log.info("There are no trade signals to check");
            return;
        }

        int totalSignals = signals.size();
        int openBeforeCheck = (int) signals.stream().filter(this::isOpenSignal).count();

        for (TradeSignalDTO signal : signals) {
            if (!isOpenSignal(signal)) {
                continue;
            }

            checkSignal(signal);
        }

        List<TradeSignalDTO> hitTpSignals = signals.stream()
                .filter(signal -> signal.getStatus() == SignalStatus.TAKE_PROFIT_HIT)
                .toList();

        List<TradeSignalDTO> hitSlSignals = signals.stream()
                .filter(signal -> signal.getStatus() == SignalStatus.STOP_LOSS_HIT)
                .toList();

        persistResolvedSignals(hitTpSignals, hitSlSignals);

        int openAfterCheck = openBeforeCheck - hitTpSignals.size() - hitSlSignals.size();

        log.info(
                "Signal check result -> total={}, openBefore={}, openAfter={}, hitTp={}, hitSl={}",
                totalSignals,
                openBeforeCheck,
                openAfterCheck,
                hitTpSignals.size(),
                hitSlSignals.size()
        );
    }

    private void checkSignal(TradeSignalDTO signal) {

        try {
            if (!isValidSignal(signal)) {
                log.warn("Skipping invalid trade signal: {}", signal);
                return;
            }

            List<CandleDTO> candles = loadHourlyCandlesSinceSignal(signal);

            if (candles.isEmpty()) {
                log.debug("No candles available after signal for symbol={}", signal.getSymbol());
                return;
            }

            for (CandleDTO candle : candles) {
                SignalResolution resolution = evaluateCandle(signal, candle);

                if (resolution == null) {
                    continue;
                }

                applyResolution(signal, resolution);

                log.info(
                        "Signal resolved symbol={}, status={}, resolutionTimestamp={}, candleTimestamp={}",
                        signal.getSymbol(),
                        signal.getStatus(),
                        signal.getResolvedAtTimestamp(),
                        candle.getTimestamp()
                );

                return;
            }

        } catch (Exception e) {
            log.error("Error checking signal for symbol={}: {}", signal.getSymbol(), e.getMessage(), e);
        }
    }

    private SignalResolution evaluateCandle(TradeSignalDTO signal, CandleDTO candle) {

        boolean hitTakeProfit = isGreaterThanOrEqual(candle.getHigh(), signal.getTakeProfit());
        boolean hitStopLoss = isLessThanOrEqual(candle.getLow(), signal.getStopLoss());

        if (!hitTakeProfit && !hitStopLoss) {
            return null;
        }

        if (hitTakeProfit && !hitStopLoss) {
            return new SignalResolution(SignalStatus.TAKE_PROFIT_HIT, candle.getTimestamp());
        }

        if (hitStopLoss && !hitTakeProfit) {
            return new SignalResolution(SignalStatus.STOP_LOSS_HIT, candle.getTimestamp());
        }

        /*
         * Both TP and SL are inside the same 1-hour candle.
         *
         * Hourly OHLC data cannot tell us which price was reached first,
         * therefore inspect the corresponding 1-minute candles.
         */
        return resolveAmbiguousHourlyCandle(signal, candle);
    }

    private SignalResolution resolveAmbiguousHourlyCandle(TradeSignalDTO signal, CandleDTO hourlyCandle) {

        List<CandleDTO> minuteCandles = exchangeClient.fetchMinutelyClosingPricesWithTimestamp(
                signal.getSymbol(),
                1,
                MINUTES_PER_HOUR,
                hourlyCandle.getTimestamp()
        );

        if (minuteCandles == null || minuteCandles.isEmpty()) {
            log.warn(
                    "Unable to resolve TP/SL order using minute data for symbol={}, hour={}. Using conservative SL",
                    signal.getSymbol(),
                    hourlyCandle.getTimestamp()
            );

            return new SignalResolution(SignalStatus.STOP_LOSS_HIT, hourlyCandle.getTimestamp());
        }

        long hourEndTimestamp = hourlyCandle.getTimestamp() + SECONDS_PER_HOUR;

        List<CandleDTO> sortedMinuteCandles = minuteCandles.stream()
                .filter(candle -> candle.getTimestamp() >= hourlyCandle.getTimestamp())
                .filter(candle -> candle.getTimestamp() < hourEndTimestamp)
                .sorted(Comparator.comparingLong(CandleDTO::getTimestamp))
                .toList();

        for (CandleDTO candle : sortedMinuteCandles) {
            boolean hitTakeProfit = isGreaterThanOrEqual(candle.getHigh(), signal.getTakeProfit());
            boolean hitStopLoss = isLessThanOrEqual(candle.getLow(), signal.getStopLoss());

            if (!hitTakeProfit && !hitStopLoss) {
                continue;
            }

            if (hitTakeProfit && !hitStopLoss) {
                return new SignalResolution(SignalStatus.TAKE_PROFIT_HIT, candle.getTimestamp());
            }

            if (hitStopLoss && !hitTakeProfit) {
                return new SignalResolution(SignalStatus.STOP_LOSS_HIT, candle.getTimestamp());
            }

            /*
             * Both TP and SL were reached inside the same 1-minute candle.
             *
             * Without tick data we still cannot know the true execution order.
             * For backtesting/signal evaluation, use the conservative assumption
             * that stop loss was reached first.
             */
            log.debug(
                    "TP and SL reached in same minute for symbol={}, timestamp={}. Using conservative SL",
                    signal.getSymbol(),
                    candle.getTimestamp()
            );

            return new SignalResolution(SignalStatus.STOP_LOSS_HIT, candle.getTimestamp());
        }

        /*
         * Hourly candle said both levels were touched, but minute data could not reproduce it.
         * This can happen because of exchange data inconsistencies.
         */
        log.warn(
                "Hourly/minute candle mismatch for symbol={}, timestamp={}. Using conservative SL",
                signal.getSymbol(),
                hourlyCandle.getTimestamp()
        );

        return new SignalResolution(SignalStatus.STOP_LOSS_HIT, hourlyCandle.getTimestamp());
    }

    private List<CandleDTO> loadHourlyCandlesSinceSignal(TradeSignalDTO signal) {

        long signalTimestamp = signal.getTimestamp();
        long nowTimestamp = Instant.now().getEpochSecond();

        if (signalTimestamp >= nowTimestamp) {
            return List.of();
        }

        List<CandleDTO> result = new ArrayList<>();
        long cursor = signalTimestamp;

        while (cursor < nowTimestamp) {
            List<CandleDTO> batch = exchangeClient.fetchHourlyClosingPricesWithTimestamp(
                    signal.getSymbol(),
                    HOURLY_INTERVAL,
                    HOURLY_BATCH_SIZE,
                    cursor
            );

            if (batch == null || batch.isEmpty()) {
                break;
            }

            List<CandleDTO> sortedBatch = batch.stream()
                    .filter(this::isValidCandle)
                    .filter(candle -> candle.getTimestamp() >= signalTimestamp)
                    .filter(candle -> candle.getTimestamp() <= nowTimestamp)
                    .sorted(Comparator.comparingLong(CandleDTO::getTimestamp))
                    .toList();

            if (sortedBatch.isEmpty()) {
                break;
            }

            for (CandleDTO candle : sortedBatch) {
                if (result.isEmpty() || candle.getTimestamp() > result.getLast().getTimestamp()) {
                    result.add(candle);
                }
            }

            long lastTimestamp = sortedBatch.getLast().getTimestamp();
            long nextCursor = lastTimestamp + SECONDS_PER_HOUR;

            if (nextCursor <= cursor) {
                break;
            }

            cursor = nextCursor;

            if (sortedBatch.size() < HOURLY_BATCH_SIZE) {
                break;
            }
        }

        return result;
    }

    private void applyResolution(TradeSignalDTO signal, SignalResolution resolution) {
        signal.setStatus(resolution.status());
        signal.setResolvedAtTimestamp(resolution.timestamp());
    }

    private void persistResolvedSignals(List<TradeSignalDTO> hitTpSignals, List<TradeSignalDTO> hitSlSignals) {

        if (!hitTpSignals.isEmpty()) {
            DataUtils.saveHitTpSignals(hitTpSignals);
        }

        if (!hitSlSignals.isEmpty()) {
            DataUtils.saveHitSlSignals(hitSlSignals);
        }

        List<TradeSignalDTO> resolvedSignals = new ArrayList<>(hitTpSignals.size() + hitSlSignals.size());
        resolvedSignals.addAll(hitTpSignals);
        resolvedSignals.addAll(hitSlSignals);

        if (!resolvedSignals.isEmpty()) {
            DataUtils.removeTradeSignals(resolvedSignals);
        }
    }

    private boolean isOpenSignal(TradeSignalDTO signal) {
        return signal != null && signal.getStatus() == SignalStatus.OPEN;
    }

    private boolean isValidSignal(TradeSignalDTO signal) {

        if (signal == null || signal.getSymbol() == null || signal.getSymbol().isBlank()) {
            return false;
        }

        if (signal.getTimestamp() <= 0 || signal.getEntryPrice() == null
                || signal.getTakeProfit() == null || signal.getStopLoss() == null) {
            return false;
        }

        if (signal.getEntryPrice().compareTo(BigDecimal.ZERO) <= 0
                || signal.getTakeProfit().compareTo(BigDecimal.ZERO) <= 0
                || signal.getStopLoss().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        if (signal.getStopLoss().compareTo(signal.getEntryPrice()) >= 0) {
            return false;
        }

        return signal.getTakeProfit().compareTo(signal.getEntryPrice()) > 0;
    }

    private boolean isGreaterThanOrEqual(double value, BigDecimal target) {
        return BigDecimal.valueOf(value).compareTo(target) >= 0;
    }

    private boolean isLessThanOrEqual(double value, BigDecimal target) {
        return BigDecimal.valueOf(value).compareTo(target) <= 0;
    }

    private boolean isValidCandle(CandleDTO candle) {

        if (candle == null || candle.getTimestamp() <= 0) {
            return false;
        }

        double open = candle.getOpen();
        double high = candle.getHigh();
        double low = candle.getLow();
        double close = candle.getClose();

        return Double.isFinite(open)
                && Double.isFinite(high)
                && Double.isFinite(low)
                && Double.isFinite(close)
                && open > 0
                && high > 0
                && low > 0
                && close > 0
                && high >= Math.max(open, close)
                && low <= Math.min(open, close);
    }

    private record SignalResolution(SignalStatus status, long timestamp) {
    }
}