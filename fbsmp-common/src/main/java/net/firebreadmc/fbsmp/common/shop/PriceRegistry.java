package net.firebreadmc.fbsmp.common.shop;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable, validated price table for the shop and sell systems.
 *
 * <p>Prices are integer minor units (the project's money type). A rule is one of:
 * <ul>
 *   <li>{@link Source#EXPLICIT} — configured by the owner;</li>
 *   <li>{@link Source#GENERATED} — derived from the item's {@link PriceTier};</li>
 *   <li>{@link Source#RESTRICTED} — never buyable or sellable by default;</li>
 *   <li>{@link Source#DISABLED} — explicitly turned off.</li>
 * </ul>
 *
 * <p>Fail-closed: an item with no rule is neither buyable nor sellable and is
 * reported for review. A generated price is only produced for items that pass
 * {@link ItemClassifier} without a review flag.
 */
public final class PriceRegistry {

    public enum Source { EXPLICIT, GENERATED, RESTRICTED, DISABLED }

    /** Default fraction of the buy price that a player receives when selling. */
    public static final BigDecimal DEFAULT_SELL_RATIO = new BigDecimal("0.50");

    public record Rule(String itemId, ItemCategory category, PriceTier tier,
                       Long buyMinor, Long sellMinor, boolean restricted,
                       boolean requiresReview, Source source) {
        public boolean buyable() {
            return !restricted && source != Source.DISABLED && buyMinor != null && buyMinor > 0;
        }

        public boolean sellable() {
            return !restricted && source != Source.DISABLED && sellMinor != null && sellMinor > 0;
        }
    }

    private final Map<String, Rule> rules;
    private final Map<String, Integer> order;
    private final Set<String> disabled;

    private PriceRegistry(Map<String, Rule> rules, Map<String, Integer> order, Set<String> disabled) {
        // LinkedHashMap over the builder's TreeMap keeps rules() in a stable,
        // deterministic order; Map.copyOf would leave it unspecified.
        this.rules = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(rules));
        this.order = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(order));
        this.disabled = Set.copyOf(disabled);
    }

    /** Curated display position of an item, or {@link Integer#MAX_VALUE} when unset. */
    public int orderIndex(String itemId) {
        Integer index = order.get(ItemClassifier.normalize(itemId));
        return index == null ? Integer.MAX_VALUE : index;
    }

    public Rule rule(String itemId) {
        return rules.get(ItemClassifier.normalize(itemId));
    }

    public boolean isBuyable(String itemId) {
        Rule rule = rule(itemId);
        return rule != null && rule.buyable();
    }

    public boolean isSellable(String itemId) {
        Rule rule = rule(itemId);
        return rule != null && rule.sellable();
    }

    public Long buyPrice(String itemId) {
        Rule rule = rule(itemId);
        return rule == null ? null : rule.buyMinor();
    }

    public Long sellPrice(String itemId) {
        Rule rule = rule(itemId);
        return rule == null ? null : rule.sellMinor();
    }

    public boolean needsReview(String itemId) {
        Rule rule = rule(itemId);
        return rule == null || rule.requiresReview();
    }

    public Set<String> disabledItems() {
        return disabled;
    }

    public List<Rule> rules() {
        return List.copyOf(rules.values());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder; call {@link #build()} to validate and freeze. */
    public static final class Builder {
        private final Map<String, Long> explicitBuy = new LinkedHashMap<>();
        private final Map<String, Long> explicitSell = new LinkedHashMap<>();
        private final Map<String, ItemCategory> categoryOverrides = new LinkedHashMap<>();
        private final Map<String, Integer> order = new LinkedHashMap<>();
        private final Set<String> disabled = new java.util.LinkedHashSet<>();
        private BigDecimal sellRatio = DEFAULT_SELL_RATIO;

        public Builder explicitBuy(String itemId, long buyMinor) {
            explicitBuy.put(ItemClassifier.normalize(itemId), buyMinor);
            return this;
        }

        public Builder explicitSell(String itemId, long sellMinor) {
            explicitSell.put(ItemClassifier.normalize(itemId), sellMinor);
            return this;
        }

        public Builder category(String itemId, ItemCategory category) {
            categoryOverrides.put(ItemClassifier.normalize(itemId), category);
            return this;
        }

        /** Records a curated display position; later calls win. */
        public Builder order(String itemId, int index) {
            order.put(ItemClassifier.normalize(itemId), index);
            return this;
        }

        public Builder disable(String itemId) {
            disabled.add(ItemClassifier.normalize(itemId));
            return this;
        }

        public Builder sellRatio(BigDecimal ratio) {
            if (ratio == null || ratio.signum() < 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("Sell ratio must be between 0 and 1.");
            }
            this.sellRatio = ratio;
            return this;
        }

        /**
         * Builds rules for the whole registry. {@code registryIds} is every valid
         * namespaced item id the server knows about.
         */
        public PriceRegistry build(Collection<String> registryIds) {
            Map<String, Rule> rules = new TreeMap<>();
            for (String rawId : registryIds) {
                String id = ItemClassifier.normalize(rawId);
                if (id.isEmpty() || rules.containsKey(id)) {
                    continue;
                }
                rules.put(id, buildRule(id));
            }
            // Explicit-only items not present in the registry are still honoured.
            for (String id : explicitBuy.keySet()) {
                rules.computeIfAbsent(id, this::buildRule);
            }
            for (String id : explicitSell.keySet()) {
                rules.computeIfAbsent(id, this::buildRule);
            }
            return new PriceRegistry(rules, order, disabled);
        }

        private Rule buildRule(String id) {
            ItemClassifier.Classification classification = ItemClassifier.classify(id);
            ItemCategory category = categoryOverrides.getOrDefault(id, classification.category());
            PriceTier tier = classification.tier();
            boolean restricted = classification.restricted();
            boolean requiresReview = classification.requiresReview();
            Source source;

            Long explicitBuyPrice = positiveOrNull(explicitBuy.get(id));
            Long explicitSellPrice = positiveOrNull(explicitSell.get(id));

            if (disabled.contains(id)) {
                return new Rule(id, category, tier, explicitBuyPrice, explicitSellPrice,
                        restricted, requiresReview, Source.DISABLED);
            }
            if (restricted) {
                return new Rule(id, category, tier, null, null, true, false, Source.RESTRICTED);
            }
            if (explicitBuyPrice != null || explicitSellPrice != null) {
                Long buy = explicitBuyPrice;
                Long sell = explicitSellPrice;
                if (buy == null && sell != null) {
                    // A sell-only item: derive a buy price so the spread is sane.
                    buy = deriveBuyFromSell(sell);
                }
                if (sell == null && buy != null && !requiresReview) {
                    sell = deriveSell(buy);
                }
                return new Rule(id, category, tier, buy, sell, false, requiresReview, Source.EXPLICIT);
            }
            if (requiresReview) {
                return new Rule(id, category, tier, null, null, false, true, Source.GENERATED);
            }
            long buy = tier.midpointMinor();
            return new Rule(id, category, tier, buy, deriveSell(buy), false, false, Source.GENERATED);
        }

        private long deriveBuyFromSell(long sellMinor) {
            BigDecimal buy = BigDecimal.valueOf(sellMinor)
                    .divide(sellRatio, 0, RoundingMode.CEILING);
            return Math.max(sellMinor + 1, buy.longValueExact());
        }

        /** Returns null when no positive sell price can stay strictly below the buy price. */
        private Long deriveSell(long buyMinor) {
            if (buyMinor <= 1) {
                return null;
            }
            long sell = BigDecimal.valueOf(buyMinor)
                    .multiply(sellRatio)
                    .setScale(0, RoundingMode.FLOOR)
                    .longValueExact();
            return Math.max(1, Math.min(sell, buyMinor - 1));
        }

        private static Long positiveOrNull(Long value) {
            return value != null && value > 0 ? value : null;
        }
    }
}
