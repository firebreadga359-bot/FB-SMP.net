package net.firebreadmc.fbsmp.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class FbCalendar {
    private static final List<String> MONTHS = List.of(
            "Frostwake", "Thawrise", "Bloomtide", "Suncrest", "Highsummer",
            "Goldleaf", "Harvestfall", "Duskmere", "Longnight", "Emberend", "Starfall", "Yearsend"
    );
    private static final DateTimeFormatter SERVER_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);

    private FbCalendar() {}

    public static String date(long calendarStartMillis, int dayLengthMinutes, int specialMonthDays) {
        long safeLength = Math.max(1, dayLengthMinutes) * 60_000L;
        long elapsedDays = Math.max(0, (System.currentTimeMillis() - calendarStartMillis) / safeLength);
        int yearDays = 11 * 30 + Math.max(1, Math.min(30, specialMonthDays));
        long year = elapsedDays / yearDays + 1;
        int dayOfYear = (int) (elapsedDays % yearDays);
        int month;
        int day;
        if (dayOfYear >= 11 * 30) {
            month = 11;
            day = dayOfYear - 11 * 30 + 1;
        } else {
            month = dayOfYear / 30;
            day = dayOfYear % 30 + 1;
        }
        return "Year " + year + ", " + MONTHS.get(month) + " " + day;
    }

    public static String isoTimestamp(long timestamp) {
        return SERVER_DATE.format(Instant.ofEpochMilli(timestamp));
    }

    public static String monthName(int monthIndex) {
        if (monthIndex < 0 || monthIndex >= MONTHS.size()) {
            throw new IllegalArgumentException("Month index must be between 0 and 11.");
        }
        return MONTHS.get(monthIndex);
    }
}