package org.agent.utils;

import lombok.extern.slf4j.Slf4j;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;

@Slf4j
public class TradeUtils {

    private static final String ISO_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final SimpleDateFormat ISO_SIMPLE_DATE_FORMAT = new SimpleDateFormat(ISO_DATE_FORMAT);

    public static long getHourTimestamp(int hours, int count) {
        ZonedDateTime nowUtc = Instant.now().atZone(ZoneOffset.UTC);
        long now = nowUtc.toEpochSecond();
        long interval = (long) hours * 60 * 60;
        return (now / interval - count) * interval;
    }

    public static long getMinuteTimestamp(int minutes, int count) {
        ZonedDateTime nowUtc = Instant.now().atZone(ZoneOffset.UTC);
        long now = nowUtc.toEpochSecond();
        long interval = (long) minutes * 60;
        return (now / interval - count) * interval;
    }

    public static long getDayTimestamp(int days, int count) {
        ZonedDateTime nowUtc = Instant.now().atZone(ZoneOffset.UTC);
        long now = nowUtc.toEpochSecond();
        long interval = days * 24 * 60 * 60L;
        return (now / interval - count) * interval;
    }

    public static String formatTimestamp(long timestamp) {
        return ISO_SIMPLE_DATE_FORMAT.format(Date.from(Instant.ofEpochSecond(timestamp)));
    }

    public static String formatNowZonedDateTime() {
        long timestamp = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond();
        return formatTimestamp(timestamp);
    }

    public static int calculateMinutesUntilNow(long timestamp) {
        Instant past = Instant.ofEpochSecond(timestamp).atOffset(ZoneOffset.UTC).toInstant();
        Instant now = Instant.now(); // also in UTC
        return (int) Duration.between(past, now).toMinutes();
    }

    public static int calculateHoursUntilNow(long timestamp) {
        ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC);
        ZonedDateTime newZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC);
        return (int) Duration.between(zonedDateTime, newZonedDateTime).toHours();
    }
}
