package org.agent.utils;

import lombok.experimental.UtilityClass;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@UtilityClass
public final class TradeUtils {

    private static final int SECONDS_PER_MINUTE = 60;
    private static final int SECONDS_PER_HOUR = 60 * 60;
    private static final int SECONDS_PER_DAY = 24 * 60 * 60;

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    public static long getHourTimestamp(int hours, int count) {
        validatePositive(hours, "hours");
        validatePositive(count, "count");

        long intervalSeconds = (long) hours * SECONDS_PER_HOUR;
        return getHistoricalAlignedTimestamp(intervalSeconds, count);
    }

    public static long getMinuteTimestamp(int minutes, int count) {
        validatePositive(minutes, "minutes");
        validatePositive(count, "count");

        long intervalSeconds = (long) minutes * SECONDS_PER_MINUTE;
        return getHistoricalAlignedTimestamp(intervalSeconds, count);
    }

    public static long getDayTimestamp(int days, int count) {
        validatePositive(days, "days");
        validatePositive(count, "count");

        long intervalSeconds = (long) days * SECONDS_PER_DAY;
        return getHistoricalAlignedTimestamp(intervalSeconds, count);
    }

    public static String formatTimestamp(long timestamp) {
        validateTimestamp(timestamp);
        return DATE_TIME_FORMATTER.format(Instant.ofEpochSecond(timestamp));
    }

    public static String formatNowUtc() {
        return DATE_TIME_FORMATTER.format(Instant.now());
    }

    public static long calculateMinutesUntilNow(long timestamp) {
        validateTimestamp(timestamp);
        return Duration.between(Instant.ofEpochSecond(timestamp), Instant.now()).toMinutes();
    }

    public static long calculateHoursUntilNow(long timestamp) {
        validateTimestamp(timestamp);
        return Duration.between(Instant.ofEpochSecond(timestamp), Instant.now()).toHours();
    }

    public static long calculateSecondsUntilNow(long timestamp) {
        validateTimestamp(timestamp);
        return Duration.between(Instant.ofEpochSecond(timestamp), Instant.now()).toSeconds();
    }

    private static long getHistoricalAlignedTimestamp(long intervalSeconds, int count) {

        long now = Instant.now().getEpochSecond();
        long currentIntervalStart = Math.floorDiv(now, intervalSeconds) * intervalSeconds;

        return currentIntervalStart - ((long) count * intervalSeconds);
    }

    private static void validatePositive(int value, String parameterName) {
        if (value <= 0) {
            throw new IllegalArgumentException(parameterName + " must be greater than zero");
        }
    }

    private static void validateTimestamp(long timestamp) {
        if (timestamp <= 0) {
            throw new IllegalArgumentException("Timestamp must be greater than zero");
        }
    }
}