package net.firebreadmc.fbsmp.common;

import java.util.List;

/**
 * Authoritative FB-SMP calendar math for the loan system.
 *
 * <p>The live server's calendar is time-derived (see {@link FbCalendar}): a day is
 * {@code dayLengthMinutes} of real time from a stored epoch. This class turns that
 * model into a monotonic integer day index and a {@link Date} value object so the
 * loan system can do exact date arithmetic without touching world time.
 *
 * <p>Shape (matches the existing calendar): 12 named months, months 1..11 are
 * {@code regularMonthDays} long and month 12 (Yearsend) is {@code specialMonthDays}
 * long. Years are 1-based, months 1..12, days 1-based. The day index is 0-based and
 * counts from the calendar epoch.
 *
 * <p>This is pure arithmetic: no wall clock, no world time, no I/O. Callers pass
 * {@code now} explicitly so the class is deterministic and unit-testable.
 */
public final class FbsmpCalendar {

    public static final int MONTH_COUNT = 12;
    private static final List<String> MONTH_NAMES = List.of(
            "Frostwake", "Thawrise", "Bloomtide", "Suncrest", "Highsummer",
            "Goldleaf", "Harvestfall", "Duskmere", "Longnight", "Emberend", "Starfall", "Yearsend");

    private final int regularMonthDays;
    private final int specialMonthDays;
    private final int yearDays;

    public FbsmpCalendar(int regularMonthDays, int specialMonthDays) {
        if (regularMonthDays < 1 || specialMonthDays < 1) {
            throw new IllegalArgumentException("Month lengths must be positive.");
        }
        this.regularMonthDays = regularMonthDays;
        this.specialMonthDays = specialMonthDays;
        this.yearDays = (MONTH_COUNT - 1) * regularMonthDays + specialMonthDays;
    }

    public static FbsmpCalendar fromConfig(int regularMonthDays, int specialMonthDays) {
        return new FbsmpCalendar(regularMonthDays, specialMonthDays);
    }

    public int yearDays() {
        return yearDays;
    }

    public int monthLength(int month) {
        requireMonth(month);
        return month == MONTH_COUNT ? specialMonthDays : regularMonthDays;
    }

    public String monthName(int month) {
        requireMonth(month);
        return MONTH_NAMES.get(month - 1);
    }

    /** 0-based monotonic day index for a date. */
    public long dayIndex(Date date) {
        requireValid(date);
        long days = (long) (date.year() - 1) * yearDays;
        for (int month = 1; month < date.month(); month++) {
            days += monthLength(month);
        }
        return days + (date.day() - 1);
    }

    public Date fromDayIndex(long dayIndex) {
        if (dayIndex < 0) {
            throw new IllegalArgumentException("Day index must not be negative.");
        }
        long year = dayIndex / yearDays + 1;
        int dayOfYear = (int) (dayIndex % yearDays);
        int month = 1;
        while (dayOfYear >= monthLength(month)) {
            dayOfYear -= monthLength(month);
            month++;
        }
        return new Date(year, month, dayOfYear + 1);
    }

    public boolean isValid(long year, int month, int day) {
        return year >= 1
                && month >= 1 && month <= MONTH_COUNT
                && day >= 1 && day <= monthLength(month);
    }

    public void requireValid(Date date) {
        if (date == null || !isValid(date.year(), date.month(), date.day())) {
            throw new IllegalArgumentException("Date is outside the FB-SMP calendar.");
        }
    }

    /** Signed difference in calendar days: {@code to - from}. */
    public long daysBetween(Date from, Date to) {
        return dayIndex(to) - dayIndex(from);
    }

    public Date addDays(Date date, long days) {
        long result = dayIndex(date) + days;
        if (result < 0) {
            throw new IllegalArgumentException("Resulting date precedes the calendar epoch.");
        }
        return fromDayIndex(result);
    }

    /** Current calendar date for a real timestamp. */
    public Date today(long nowMillis, long calendarStartMillis, int dayLengthMinutes) {
        if (dayLengthMinutes < 1) {
            throw new IllegalArgumentException("Day length must be positive.");
        }
        long dayMillis = dayLengthMinutes * 60_000L;
        long elapsed = Math.max(0L, nowMillis - calendarStartMillis) / dayMillis;
        return fromDayIndex(elapsed);
    }

    /** Project display format, e.g. {@code "Year 6, Frostwake 1"}. */
    public String format(Date date) {
        requireValid(date);
        return "Year " + date.year() + ", " + monthName(date.month()) + " " + date.day();
    }

    /** Canonical dotted format used by the loan spec, e.g. {@code "1.1.6"}. */
    public String formatDotted(Date date) {
        requireValid(date);
        return date.day() + "." + date.month() + "." + date.year();
    }

    /**
     * Strict parser. Accepts either the canonical dotted form {@code d.m.y}
     * (e.g. {@code 1.1.6}) or the project display form
     * {@code Year Y, <MonthName> D}. Leading zeros are rejected in the dotted
     * form to keep one canonical spelling. Returns {@code null} for anything
     * that does not parse; use {@link ParseResult} for a reason.
     */
    public Date parse(String input) {
        ParseResult result = parseDetailed(input);
        return result.date();
    }

    public ParseResult parseDetailed(String input) {
        if (input == null) {
            return ParseResult.failure(Reason.MALFORMED);
        }
        String text = input.trim();
        if (text.isEmpty()) {
            return ParseResult.failure(Reason.MALFORMED);
        }
        if (text.startsWith("Year ")) {
            return parseDisplayForm(text);
        }
        String[] parts = text.split("\\.", -1);
        if (parts.length != 3) {
            return ParseResult.failure(Reason.MALFORMED);
        }
        long[] numbers = new long[3];
        for (int i = 0; i < 3; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                return ParseResult.failure(Reason.MALFORMED);
            }
            if (!part.chars().allMatch(Character::isDigit)) {
                return ParseResult.failure(Reason.NOT_NUMERIC);
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return ParseResult.failure(Reason.MALFORMED);
            }
            if (part.length() > 9) {
                return ParseResult.failure(Reason.OVERFLOW);
            }
            numbers[i] = Long.parseLong(part);
        }
        long day = numbers[0];
        long month = numbers[1];
        long year = numbers[2];
        if (year < 1 || year > 999_999_999L) {
            return ParseResult.failure(Reason.CALENDAR);
        }
        if (month < 1 || month > MONTH_COUNT || day < 1 || day > monthLength((int) month)) {
            return ParseResult.failure(Reason.CALENDAR);
        }
        return ParseResult.success(new Date(year, (int) month, (int) day));
    }

    private ParseResult parseDisplayForm(String text) {
        int comma = text.indexOf(", ");
        if (comma < 0) {
            return ParseResult.failure(Reason.MALFORMED);
        }
        String yearPart = text.substring("Year ".length(), comma).trim();
        String rest = text.substring(comma + 2).trim();
        int lastSpace = rest.lastIndexOf(' ');
        if (lastSpace < 0 || yearPart.isEmpty()) {
            return ParseResult.failure(Reason.MALFORMED);
        }
        String monthName = rest.substring(0, lastSpace);
        String dayPart = rest.substring(lastSpace + 1);
        int month = MONTH_NAMES.indexOf(monthName) + 1;
        if (month == 0 || !yearPart.chars().allMatch(Character::isDigit)
                || !dayPart.chars().allMatch(Character::isDigit)) {
            return ParseResult.failure(Reason.NOT_NUMERIC);
        }
        if ((yearPart.length() > 1 && yearPart.charAt(0) == '0')
                || (dayPart.length() > 1 && dayPart.charAt(0) == '0')) {
            return ParseResult.failure(Reason.MALFORMED);
        }
        try {
            long year = Long.parseLong(yearPart);
            int day = Integer.parseInt(dayPart);
            if (!isValid(year, month, day)) {
                return ParseResult.failure(Reason.CALENDAR);
            }
            return ParseResult.success(new Date(year, month, day));
        } catch (NumberFormatException exception) {
            return ParseResult.failure(Reason.OVERFLOW);
        }
    }

    private void requireMonth(int month) {
        if (month < 1 || month > MONTH_COUNT) {
            throw new IllegalArgumentException("Month must be between 1 and 12.");
        }
    }

    /** Immutable 1-based calendar date. */
    public record Date(long year, int month, int day) {
        public Date {
            if (year < 1) {
                throw new IllegalArgumentException("Year must be positive.");
            }
        }
    }

    public enum Reason { MALFORMED, NOT_NUMERIC, CALENDAR, OVERFLOW }

    public record ParseResult(Date date, Reason reason) {
        public static ParseResult success(Date date) {
            return new ParseResult(date, null);
        }

        public static ParseResult failure(Reason reason) {
            return new ParseResult(null, reason);
        }

        public boolean ok() {
            return date != null;
        }
    }
}
