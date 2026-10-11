package net.firebreadmc.fbsmp.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Simple, explicit, non-compounding loan interest.
 *
 * <p>Interest depends only on the principal and the selected repayment period,
 * never on elapsed time or on later re-payment. Two models are supported:
 *
 * <ul>
 *   <li>{@link Model#LINEAR}: {@code rate = min(maxRate, baseRate + perDayRate * periodDays)}</li>
 *   <li>{@link Model#TIERED}: the first configured tier whose {@code upToDays} covers the
 *       period, falling back to the last tier for longer periods.</li>
 * </ul>
 *
 * <p>Amounts are integer minor units (the project's money type). Interest is rounded
 * with the configured {@link RoundingMode} to whole minor units and never goes below
 * {@code minInterest}. The calculation is pure and deterministic.
 */
public final class InterestCalculator {

    public enum Model { LINEAR, TIERED }

    public record Tier(int upToDays, BigDecimal rate) {}

    public record Config(
            Model model,
            BigDecimal baseRate,
            BigDecimal perDayRate,
            BigDecimal maxRate,
            RoundingMode roundingMode,
            long minInterest,
            List<Tier> tiers) {

        public Config {
            if (model == null || baseRate == null || perDayRate == null || maxRate == null) {
                throw new IllegalArgumentException("Interest configuration is incomplete.");
            }
            if (baseRate.signum() < 0 || perDayRate.signum() < 0 || maxRate.signum() < 0) {
                throw new IllegalArgumentException("Interest rates must not be negative.");
            }
            if (maxRate.signum() < 0) {
                throw new IllegalArgumentException("maxRate must not be negative.");
            }
            roundingMode = roundingMode == null ? RoundingMode.HALF_UP : roundingMode;
            minInterest = Math.max(0, minInterest);
            tiers = tiers == null ? List.of() : List.copyOf(tiers);
            validateTiers(model, tiers);
        }
    }

    public record Result(BigDecimal rate, long interest, long totalDue) {}

    private final Config config;

    public InterestCalculator(Config config) {
        this.config = config;
        validateMonotonic(config);
    }

    /** Interest rate for a period, before any minimum-interest floor. */
    public BigDecimal rateFor(int periodDays) {
        if (periodDays < 1) {
            throw new IllegalArgumentException("Repayment period must be at least one day.");
        }
        if (config.model() == Model.LINEAR) {
            BigDecimal rate = config.baseRate()
                    .add(config.perDayRate().multiply(BigDecimal.valueOf(periodDays)));
            return rate.min(config.maxRate());
        }
        Tier selected = config.tiers().get(config.tiers().size() - 1);
        for (Tier tier : config.tiers()) {
            if (periodDays <= tier.upToDays()) {
                selected = tier;
                break;
            }
        }
        return selected.rate().min(config.maxRate());
    }

    /**
     * Computes interest and total due for a principal and period. {@code principal}
     * is in minor units and must be positive.
     */
    public Result compute(long principal, int periodDays) {
        if (principal <= 0) {
            throw new IllegalArgumentException("Principal must be positive.");
        }
        BigDecimal rate = rateFor(periodDays);
        BigDecimal interest = BigDecimal.valueOf(principal)
                .multiply(rate)
                .setScale(0, config.roundingMode());
        long interestMinor = Math.max(config.minInterest(), interest.longValueExact());
        long total = Math.addExact(principal, interestMinor);
        return new Result(rate, interestMinor, total);
    }

    private static void validateTiers(Model model, List<Tier> tiers) {
        if (model != Model.TIERED) {
            return;
        }
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("Tiered interest requires at least one tier.");
        }
        int previous = 0;
        for (Tier tier : tiers) {
            if (tier.upToDays() <= previous) {
                throw new IllegalArgumentException("Interest tiers must be strictly increasing.");
            }
            if (tier.rate().signum() < 0) {
                throw new IllegalArgumentException("Interest tier rates must not be negative.");
            }
            previous = tier.upToDays();
        }
    }

    private static void validateMonotonic(Config config) {
        // A longer period must never cost a lower rate for the same principal.
        BigDecimal previous = null;
        for (int day = 1; day <= 100; day++) {
            BigDecimal rate = day <= 0 ? null : rateForInternal(config, day);
            if (previous != null && rate.compareTo(previous) < 0) {
                throw new IllegalArgumentException(
                        "Interest rate must not decrease as the period grows (day " + day + ").");
            }
            previous = rate;
        }
    }

    private static BigDecimal rateForInternal(Config config, int periodDays) {
        if (config.model() == Model.LINEAR) {
            return config.baseRate()
                    .add(config.perDayRate().multiply(BigDecimal.valueOf(periodDays)))
                    .min(config.maxRate());
        }
        Tier selected = config.tiers().get(config.tiers().size() - 1);
        for (Tier tier : config.tiers()) {
            if (periodDays <= tier.upToDays()) {
                selected = tier;
                break;
            }
        }
        return selected.rate().min(config.maxRate());
    }
}
