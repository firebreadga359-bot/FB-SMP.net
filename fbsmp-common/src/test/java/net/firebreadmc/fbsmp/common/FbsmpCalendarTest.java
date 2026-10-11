package net.firebreadmc.fbsmp.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FbsmpCalendarTest {
    private final FbsmpCalendar calendar = FbsmpCalendar.fromConfig(30, 29);

    @Test
    void yearHasElevenThirtyDayMonthsAndA29DayYearsend() {
        assertEquals(359, calendar.yearDays());
        assertEquals(30, calendar.monthLength(1));
        assertEquals(29, calendar.monthLength(12));
    }

    @Test
    void dayIndexRoundTripsAcrossMonthAndYearBoundaries() {
        assertEquals(0, calendar.dayIndex(new FbsmpCalendar.Date(1, 1, 1)));
        assertEquals(29, calendar.dayIndex(new FbsmpCalendar.Date(1, 1, 30)));
        assertEquals(30, calendar.dayIndex(new FbsmpCalendar.Date(1, 2, 1)));
        assertEquals(330, calendar.dayIndex(new FbsmpCalendar.Date(1, 12, 1)));
        assertEquals(358, calendar.dayIndex(new FbsmpCalendar.Date(1, 12, 29)));
        assertEquals(359, calendar.dayIndex(new FbsmpCalendar.Date(2, 1, 1)));

        for (long index = 0; index < 800; index++) {
            assertEquals(index, calendar.dayIndex(calendar.fromDayIndex(index)));
        }
    }

    @Test
    void daysBetweenIsSignedAndExact() {
        var a = new FbsmpCalendar.Date(1, 12, 20);
        var b = new FbsmpCalendar.Date(2, 1, 1);
        assertEquals(10, calendar.daysBetween(a, b));
        assertEquals(-10, calendar.daysBetween(b, a));
    }

    @Test
    void exactlyOneHundredDaysIsAcceptedButLongerPeriodsAreTheCallersJob() {
        var issue = new FbsmpCalendar.Date(1, 1, 1);
        var due = calendar.addDays(issue, 100);
        assertEquals(100, calendar.daysBetween(issue, due));
    }

    @Test
    void parsesCanonicalDottedDates() {
        assertEquals(new FbsmpCalendar.Date(6, 1, 1), calendar.parse("1.1.6"));
        assertEquals(new FbsmpCalendar.Date(6, 4, 21), calendar.parse("21.4.6"));
        assertEquals("1.1.6", calendar.formatDotted(calendar.parse("1.1.6")));
    }

    @Test
    void rejectsMalformedDottedDatesWithReasons() {
        assertEquals(FbsmpCalendar.Reason.MALFORMED, calendar.parseDetailed("1.1").reason());
        assertEquals(FbsmpCalendar.Reason.MALFORMED, calendar.parseDetailed("1.1.6.2").reason());
        assertEquals(FbsmpCalendar.Reason.NOT_NUMERIC, calendar.parseDetailed("a.b.c").reason());
        assertEquals(FbsmpCalendar.Reason.MALFORMED, calendar.parseDetailed("1..6").reason());
        assertEquals(FbsmpCalendar.Reason.NOT_NUMERIC, calendar.parseDetailed("-1.1.6").reason());
        assertEquals(FbsmpCalendar.Reason.MALFORMED, calendar.parseDetailed("01.01.06").reason());
        assertEquals(FbsmpCalendar.Reason.OVERFLOW, calendar.parseDetailed("1.1.1234567890").reason());
        assertNull(calendar.parse(""));
        assertNull(calendar.parse("1.13.6"));
        assertNull(calendar.parse("30.12.6"));
    }

    @Test
    void acceptsTheProjectDisplayForm() {
        assertEquals(new FbsmpCalendar.Date(6, 1, 1), calendar.parse("Year 6, Frostwake 1"));
        assertEquals(new FbsmpCalendar.Date(6, 12, 29), calendar.parse("Year 6, Yearsend 29"));
        assertEquals("Year 6, Frostwake 1", calendar.format(new FbsmpCalendar.Date(6, 1, 1)));
    }

    @Test
    void todayIsDerivedFromRealTimeAndEpoch() {
        long start = 1_000_000_000_000L;
        long dayMillis = 30 * 60_000L;
        assertEquals(new FbsmpCalendar.Date(1, 1, 1), calendar.today(start, start, 30));
        assertEquals(new FbsmpCalendar.Date(1, 1, 2), calendar.today(start + dayMillis, start, 30));
        assertEquals(new FbsmpCalendar.Date(1, 1, 2),
                calendar.today(start + dayMillis + dayMillis - 1, start, 30));
    }
}
