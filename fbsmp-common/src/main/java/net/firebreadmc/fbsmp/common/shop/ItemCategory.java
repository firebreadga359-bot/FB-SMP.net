package net.firebreadmc.fbsmp.common.shop;

/**
 * Exactly one category per catalog item. {@link #RESTRICTED} holds items that are
 * shown to admins only and are never buyable or sellable by default.
 */
public enum ItemCategory {
    BUILDING("Building"),
    DECORATION("Decoration"),
    UTILITY("Utility"),
    FARMING("Farming"),
    FOOD("Food"),
    MINING("Mining"),
    ARMOR("Armor"),
    WEAPONS("Weapons"),
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
