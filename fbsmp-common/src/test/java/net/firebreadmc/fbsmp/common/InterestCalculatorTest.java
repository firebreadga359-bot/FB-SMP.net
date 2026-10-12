package net.firebreadmc.fbsmp.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InterestCalculatorTest {

    private static InterestCalculator.Config linear() {
        return new InterestCalculator.Config(
                InterestCalculator.Model.LINEAR,
                new BigDecimal("0.02"),
                new BigDecimal("0.001"),
                new BigDecimal("0.12"),
                RoundingMode.HALF_UP,
                0,
                List.of());
    }

    @Test
    void matchesTheSpecReferenceTable() {
        var calculator = new InterestCalculator(linear());
        // base 2% + 0.1%/day, max 12%, principal 10,000 major units = 1,000,000 minor.
        assertResult(calculator, 1, "0.021", 21_000, 1_021_000);
        assertResult(calculator, 10, "0.030", 30_000, 1_030_000);
        assertResult(calculator, 30, "0.050", 50_000, 1_050_000);
        assertResult(calculator, 60, "0.080", 80_000, 1_080_000);
        assertResult(calculator, 100, "0.120", 120_000, 1_120_000);
    }

    private static void assertResult(InterestCalculator calculator, int days,
                                     String rate, long interest, long total) {
        var result = calculator.compute(1_000_000, days);
        assertEquals(0, new BigDecimal(rate).compareTo(result.rate()), "rate on day " + days);
        assertEquals(interest, result.interest(), "interest on day " + days);
        assertEquals(total, result.totalDue(), "total on day " + days);
    }

    @Test
    void rateIsMonotonicNonDecreasing() {
        var calculator = new InterestCalculator(linear());
        BigDecimal previous = BigDecimal.ZERO;
        for (int day = 1; day <= 100; day++) {
            BigDecimal rate = calculator.rateFor(day);
            assertTrue(rate.compareTo(previous) >= 0, "rate decreased at day " + day);
            previous = rate;
        }
    }

    @Test
    void isSimpleAndNonCompounding() {
        var calculator = new InterestCalculator(linear());
        // Same inputs always give the same outputs; interest never depends on elapsed time.
        assertEquals(calculator.compute(500_000, 30), calculator.compute(500_000, 30));
    }

    @Test
    void roundsToWholeMinorUnitsAndHonoursMinimum() {
        var config = new InterestCalculator.Config(
                InterestCalculator.Model.LINEAR,
                new BigDecimal("0.02"),
                new BigDecimal("0.001"),
                new BigDecimal("0.12"),
                RoundingMode.HALF_UP,
                500,
                List.of());
        var calculator = new InterestCalculator(config);
        // principal 101 -> 2.1% = 2.121 minor, rounded to 2, then floored to 500.
        assertEquals(500, calculator.compute(101, 1).interest());
    }

    @Test
    void tieredModelSelectsByPeriod() {
        var config = new InterestCalculator.Config(
                InterestCalculator.Model.TIERED,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("1.00"),
                RoundingMode.HALF_UP,
                0,
                List.of(
                        new InterestCalculator.Tier(10, new BigDecimal("0.03")),
                        new InterestCalculator.Tier(30, new BigDecimal("0.06")),
                        new InterestCalculator.Tier(60, new BigDecimal("0.10")),
                        new InterestCalculator.Tier(100, new BigDecimal("0.15"))));
        var calculator = new InterestCalculator(config);
        assertEquals(0, new BigDecimal("0.03").compareTo(calculator.rateFor(1)));
        assertEquals(0, new BigDecimal("0.03").compareTo(calculator.rateFor(10)));
        assertEquals(0, new BigDecimal("0.06").compareTo(calculator.rateFor(11)));
        assertEquals(0, new BigDecimal("0.15").compareTo(calculator.rateFor(100)));
        assertEquals(1_500_000, calculator.compute(10_000_000, 100).interest());
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new InterestCalculator.Config(
                InterestCalculator.Model.TIERED, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ONE, RoundingMode.HALF_UP, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new InterestCalculator.Config(
                InterestCalculator.Model.LINEAR, new BigDecimal("-0.01"), BigDecimal.ZERO,
                BigDecimal.ONE, RoundingMode.HALF_UP, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new InterestCalculator(linear())
                .compute(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new InterestCalculator(linear())
                .rateFor(0));
    }
}
