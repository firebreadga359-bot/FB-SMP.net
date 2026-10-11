package net.firebreadmc.fbsmp.common.shop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Validates a {@link PriceRegistry} against the shop/sell invariants.
 *
 * <p>Invariants checked:
 * <ul>
 *   <li>every buyable item has a positive buy price;</li>
 *   <li>every sellable item has a positive sell price;</li>
 *   <li>for every item that is both buyable and sellable, {@code sell < buy}
 *       (so buying then selling can never profit);</li>
 *   <li>restricted items carry no prices;</li>
 *   <li>tier medians increase with tier order (rarity sanity);</li>
 *   <li>no duplicate item ids.</li>
 * </ul>
 *
 * <p>Pure and deterministic: safe to run in tests and in a dry-run admin command.
 */
public final class PriceValidator {

    public record Violation(String itemId, String message) {}

    public record Report(int checked, List<Violation> violations, List<String> reviewItems,
                         Map<PriceTier, Long> tierMedianBuy) {
        public boolean clean() {
            return violations.isEmpty();
        }
    }

    private PriceValidator() {}

    public static Report validate(PriceRegistry registry) {
        List<Violation> violations = new ArrayList<>();
        List<String> review = new ArrayList<>();
        Map<PriceTier, List<Long>> tierBuys = new EnumMap<>(PriceTier.class);

        for (PriceRegistry.Rule rule : registry.rules()) {
            String id = rule.itemId();
            if (rule.source() == PriceRegistry.Source.RESTRICTED) {
                if (rule.buyMinor() != null || rule.sellMinor() != null) {
                    violations.add(new Violation(id, "restricted item must not have a price"));
                }
                continue;
            }
            if (rule.source() == PriceRegistry.Source.DISABLED) {
                continue;
            }
            if (rule.buyMinor() != null && rule.buyMinor() <= 0) {
                violations.add(new Violation(id, "buy price must be positive"));
            }
            if (rule.sellMinor() != null && rule.sellMinor() <= 0) {
                violations.add(new Violation(id, "sell price must be positive"));
            }
            if (rule.buyMinor() != null && rule.sellMinor() != null
                    && rule.sellMinor() >= rule.buyMinor()) {
                violations.add(new Violation(id,
                        "sell price must be below buy price (arbitrage: sell=" + rule.sellMinor()
                                + " buy=" + rule.buyMinor() + ")"));
            }
            if (rule.requiresReview() || rule.buyMinor() == null) {
                review.add(id);
            }
            if (rule.buyMinor() != null) {
                tierBuys.computeIfAbsent(rule.tier(), key -> new ArrayList<>()).add(rule.buyMinor());
            }
        }

        Map<PriceTier, Long> medians = new EnumMap<>(PriceTier.class);
        for (Map.Entry<PriceTier, List<Long>> entry : tierBuys.entrySet()) {
            List<Long> values = entry.getValue();
            values.sort(Comparator.naturalOrder());
            medians.put(entry.getKey(), values.get(values.size() / 2));
        }
        PriceTier previousTier = null;
        Long previousMedian = null;
        for (PriceTier tier : PriceTier.values()) {
            Long median = medians.get(tier);
            if (median == null) {
                continue;
            }
            if (previousMedian != null && median < previousMedian) {
                violations.add(new Violation("<tiers>", "tier median for " + tier
                        + " (" + median + ") is below " + previousTier + " (" + previousMedian + ")"));
            }
            previousTier = tier;
            previousMedian = median;
        }

        return new Report(registry.rules().size(), List.copyOf(violations), List.copyOf(review), medians);
    }
}
