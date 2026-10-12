package net.firebreadmc.fbsmp.common.sell;

/**
 * Decides whether a stack may be sold and at what value. Pure and deterministic.
 *
 * <p>Default policy (all configurable):
 * <ul>
 *   <li>renamed, lore'd, enchanted, custom-model or GUI-marker items are refused;</li>
 *   <li>non-empty containers (shulkers/bundles) are refused;</li>
 *   <li>damaged tools are priced by remaining durability and refused below a floor;</li>
 *   <li>items with no configured sell price are preserved and explained.</li>
 * </ul>
 */
public final class SellEligibilityPolicy {

    public record Config(
            boolean allowRenamed,
            boolean allowLore,
            boolean allowEnchanted,
            boolean allowCustomModel,
            boolean allowContainerContents,
            double minDurabilityFraction) {

        public Config {
            minDurabilityFraction = Math.max(0, Math.min(1, minDurabilityFraction));
        }

        public static Config defaults() {
            return new Config(false, false, false, false, false, 0.10);
        }
    }

    public enum Reason {
        ELIGIBLE,
        RESTRICTED,
        GUI_MARKER,
        CUSTOM_NAME,
        LORE,
        ENCHANTED,
        CUSTOM_MODEL,
        CONTAINER_CONTENTS,
        LOW_DURABILITY,
        UNPRICED,
        NOT_POSITIVE
    }

    public record Decision(boolean eligible, Reason reason, long unitValueMinor,
                           long totalValueMinor, long quantity, String message) {}

    private final Config config;

    public SellEligibilityPolicy(Config config) {
        this.config = config == null ? Config.defaults() : config;
    }

    /**
     * Evaluates one stack. {@code unitBuyMinor} is the shop buy price (or null);
     * {@code unitSellMinor} is the configured sell price per unit (or null).
     */
    public Decision evaluate(SellStackView stack, Long unitBuyMinor, Long unitSellMinor) {
        if (stack.amount() <= 0) {
            return refuse(Reason.NOT_POSITIVE, "There is nothing to sell.");
        }
        if (stack.isGuiMarker()) {
            return refuse(Reason.GUI_MARKER, "Menu items cannot be sold.");
        }
        if (stack.hasCustomName() && !config.allowRenamed()) {
            return refuse(Reason.CUSTOM_NAME, "Renamed items cannot be sold.");
        }
        if (stack.hasLore() && !config.allowLore()) {
            return refuse(Reason.LORE, "Items with custom lore cannot be sold.");
        }
        if (stack.hasCustomModelData() && !config.allowCustomModel()) {
            return refuse(Reason.CUSTOM_MODEL, "Custom-model items cannot be sold.");
        }
        if (stack.hasEnchantments() && !config.allowEnchanted()) {
            return refuse(Reason.ENCHANTED, "Enchanted items cannot be sold.");
        }
        if (stack.containerHasContents() && !config.allowContainerContents()) {
            return refuse(Reason.CONTAINER_CONTENTS, "Empty the container before selling it.");
        }
        if (unitSellMinor == null || unitSellMinor <= 0) {
            return refuse(Reason.UNPRICED, "This item has no sell price.");
        }
        long unitValue = unitSellMinor;
        if (stack.damageable()) {
            double remaining = (stack.maxDurability() - stack.damage())
                    / (double) stack.maxDurability();
            if (remaining < config.minDurabilityFraction()) {
                return refuse(Reason.LOW_DURABILITY, "This item is too damaged to sell.");
            }
            unitValue = Math.max(1, Math.round(unitSellMinor * remaining));
        }
        long total = Math.multiplyExact(unitValue, stack.amount());
        return new Decision(true, Reason.ELIGIBLE, unitValue, total, stack.amount(), "");
    }

    private static Decision refuse(Reason reason, String message) {
        return new Decision(false, reason, 0, 0, 0, message);
    }
}
