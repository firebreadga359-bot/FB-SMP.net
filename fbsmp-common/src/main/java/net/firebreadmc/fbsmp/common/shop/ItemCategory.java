package net.firebreadmc.fbsmp.common.shop;

/**
 * Exactly one category per catalog item. {@link #RESTRICTED} holds items that are
 * shown to admins only and are never buyable or sellable by default.
 */
public enum ItemCategory {
    BUILDING("Building"),
    FARMING("Farming"),
    MINING("Mining"),
    DECORATION("Decoration"),
    UTILITY("Utility"),
    FOOD("Food"),
    MISC("Miscellaneous"),
    RESTRICTED("Restricted");

    private final String displayName;

    ItemCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
