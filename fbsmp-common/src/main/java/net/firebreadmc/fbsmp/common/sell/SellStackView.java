package net.firebreadmc.fbsmp.common.sell;

/**
 * Describes an item stack to the sell policy without depending on server types.
 * {@code maxDurability == 0} means the item is not damageable.
 */
public record SellStackView(
        String itemId,
        int amount,
        int maxStackSize,
        boolean hasCustomName,
        boolean hasLore,
        boolean hasEnchantments,
        boolean hasCustomModelData,
        boolean containerHasContents,
        boolean isGuiMarker,
        int maxDurability,
        int damage) {

    public SellStackView {
        if (maxStackSize < 1) {
            maxStackSize = 64;
        }
        if (maxDurability < 0) {
            maxDurability = 0;
        }
        if (damage < 0) {
            damage = 0;
        }
    }

    public boolean damageable() {
        return maxDurability > 0;
    }

    public boolean pristine() {
        return !hasCustomName && !hasLore && !hasEnchantments && !hasCustomModelData
                && !containerHasContents && !isGuiMarker && damage == 0;
    }
}
