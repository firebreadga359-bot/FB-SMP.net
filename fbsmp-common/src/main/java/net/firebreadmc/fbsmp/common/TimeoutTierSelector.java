package net.firebreadmc.fbsmp.common;

import java.util.List;

/**
 * Chooses the overdue timeout tier for an outstanding debt.
 *
 * <p>Tiers are evaluated against the remaining debt at the moment the loan becomes
 * overdue (not the original principal). Tiers must be ordered, non-overlapping and
 * cover {@code (0, +infinity)}; the last tier may leave {@code maxDebt} open. Every
 * duration must be positive and is clamped to a hard maximum so a timeout can never
 * be permanent.
 */
public final class TimeoutTierSelector {

    public record Tier(long minDebt, Long maxDebt, long durationSeconds, String reason) {
        public Tier {
            if (minDebt < 0) {
                throw new IllegalArgumentException("Tier minimum debt must not be negative.");
            }
            if (durationSeconds <= 0) {
                throw new IllegalArgumentException("Tier duration must be positive.");
            }
            reason = reason == null ? "" : reason;
        }
    }

    private final List<Tier> tiers;
    private final long maxDurationSeconds;

    public TimeoutTierSelector(List<Tier> tiers, long maxDurationSeconds) {
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException("At least one timeout tier is required.");
        }
        this.tiers = List.copyOf(tiers);
        this.maxDurationSeconds = maxDurationSeconds <= 0 ? Long.MAX_VALUE : maxDurationSeconds;
        validate();
    }

    public Tier select(long remainingMinor) {
        if (remainingMinor <= 0) {
            throw new IllegalArgumentException("Outstanding debt must be positive to select a tier.");
        }
        for (Tier tier : tiers) {
            if (remainingMinor >= tier.minDebt()
                    && (tier.maxDebt() == null || remainingMinor <= tier.maxDebt())) {
                return tier;
            }
        }
        return tiers.get(tiers.size() - 1);
    }

    /** Duration actually applied, clamped to the configured maximum. */
    public long durationSeconds(Tier tier) {
        return Math.min(tier.durationSeconds(), maxDurationSeconds);
    }

    private void validate() {
        for (int i = 0; i < tiers.size(); i++) {
            Tier tier = tiers.get(i);
            if (i == 0) {
                if (tier.minDebt() > 1) {
                    throw new IllegalArgumentException(
                            "The first timeout tier must start at 0 or the smallest currency unit.");
                }
            } else {
                Tier previous = tiers.get(i - 1);
                if (previous.maxDebt() == null) {
                    throw new IllegalArgumentException("Only the final timeout tier may be open-ended.");
                }
                if (tier.minDebt() != previous.maxDebt() + 1) {
                    throw new IllegalArgumentException(
                            "Timeout tiers must be contiguous (tier " + i + ").");
                }
            }
            boolean last = i == tiers.size() - 1;
            if (last) {
                if (tier.maxDebt() != null) {
                    throw new IllegalArgumentException("The final timeout tier must be open-ended.");
                }
            } else if (tier.maxDebt() == null) {
                throw new IllegalArgumentException("Only the final timeout tier may be open-ended.");
            } else if (tier.maxDebt() < tier.minDebt()) {
                throw new IllegalArgumentException("Timeout tier maximum must not be below its minimum.");
            }
        }
    }
}
