package com.liskovsoft.smartyoutubetv2.common.local;

import java.text.DateFormat;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Strict handling for yt-dlp's original YouTube upload_date (YYYYMMDD). */
public final class LocalArchiveUploadDate {
    private LocalArchiveUploadDate() {
    }

    /** Invalid, absent, or non-canonical dates are omitted rather than guessed. */
    public static String normalize(String value) {
        if (value == null || !value.matches("[0-9]{8}")) return null;
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        ParsePosition position = new ParsePosition(0);
        Date parsed = format.parse(value, position);
        if (parsed == null || position.getIndex() != value.length() || !value.equals(format.format(parsed))) {
            return null;
        }
        return value;
    }

    /** Interpret a date-only upload value at midnight in the TV's local timezone. */
    public static long toLocalMidnightMillis(String value) {
        String normalized = normalize(value);
        if (normalized == null) return 0;
        int year = Integer.parseInt(normalized.substring(0, 4));
        int month = Integer.parseInt(normalized.substring(4, 6));
        int day = Integer.parseInt(normalized.substring(6, 8));
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.setLenient(false);
        calendar.set(year, month - 1, day, 0, 0, 0);
        try {
            return calendar.getTimeInMillis();
        } catch (IllegalArgumentException ignored) {
            return 0;
        }
    }

    public static String formatExact(String value) {
        long millis = toLocalMidnightMillis(value);
        return millis == 0 ? null : DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault())
                .format(new Date(millis));
    }
}
