package net.firebreadmc.fbsmp.paper.sell;

import net.firebreadmc.fbsmp.common.sell.SellEligibilityPolicy;
import net.firebreadmc.fbsmp.common.sell.SellPreview;
import net.firebreadmc.fbsmp.common.sell.SellStackView;
import net.firebreadmc.fbsmp.paper.menus.ShopMenu;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bridges live {@link ItemStack}s to the pure sell policy and produces sell-all
 * previews. Read-only: it never mutates an inventory. The GUI performs removal only
 * after the player confirms, and the database escrow makes the sale atomic.
 */
public final class SellService {

    /** The 36 main storage slots; hotbar included. */
    private static final int STORAGE_SLOTS = 36;

    public record Offer(int slot, ItemStack stack, String itemId, long unitValueMinor,
                        long totalValueMinor) {}

    private final ShopMenu shopMenu;
    private final SellEligibilityPolicy policy;

    public SellService(ShopMenu shopMenu, SellEligibilityPolicy policy) {
        this.shopMenu = shopMenu;
        this.policy = policy;
    }

    /** Evaluates a single stack; returns null when it is not eligible. */
    public Offer offer(int slot, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        String itemId = itemId(stack.getType());
        SellEligibilityPolicy.Decision decision = policy.evaluate(
                view(stack, itemId), shopMenu.buyPrice(itemId), shopMenu.sellPrice(itemId));
        if (!decision.eligible()) {
            return null;
        }
        return new Offer(slot, stack, itemId, decision.unitValueMinor(), decision.totalValueMinor());
    }

    public SellEligibilityPolicy.Decision evaluate(ItemStack stack) {
        String itemId = itemId(stack.getType());
        return policy.evaluate(view(stack, itemId),
                shopMenu.buyPrice(itemId), shopMenu.sellPrice(itemId));
    }

    /** All eligible offers in the player's storage slots. */
    public List<Offer> offers(Player player) {
        List<Offer> offers = new ArrayList<>();
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length && slot < STORAGE_SLOTS; slot++) {
            Offer offer = offer(slot, storage[slot]);
            if (offer != null) {
                offers.add(offer);
            }
        }
        return offers;
    }

    /**
     * Full-inventory preview: eligible totals plus the kept/refused lines. The
     * authoritative totals are recomputed at confirm time, so this is display-only.
     */
    public SellPreview previewInventory(Player player) {
        List<SellPreview.Line> lines = new ArrayList<>();
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length && slot < STORAGE_SLOTS; slot++) {
            ItemStack stack = storage[slot];
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            String itemId = itemId(stack.getType());
            lines.add(new SellPreview.Line(itemId, policy.evaluate(
                    view(stack, itemId), shopMenu.buyPrice(itemId), shopMenu.sellPrice(itemId))));
        }
        return SellPreview.of(lines);
    }

    private SellStackView view(ItemStack stack, String itemId) {
        ItemMeta meta = stack.getItemMeta();
        boolean customName = meta != null && meta.hasDisplayName();
        boolean lore = meta != null && meta.hasLore();
        boolean enchanted = meta != null && meta.hasEnchants();
        boolean customModel = meta != null && meta.hasCustomModelData();
        int damage = meta instanceof Damageable damageable ? damageable.getDamage() : 0;
        return new SellStackView(
                itemId,
                stack.getAmount(),
                stack.getType().getMaxStackSize(),
                customName,
                lore,
                enchanted,
                customModel,
                containerHasContents(stack, meta),
                shopMenu.isGuiMarker(stack),
                stack.getType().getMaxDurability(),
                damage);
    }

    private static boolean containerHasContents(ItemStack stack, ItemMeta meta) {
        if (meta instanceof BlockStateMeta blockState && blockState.getBlockState() instanceof ShulkerBox box) {
            for (ItemStack content : box.getInventory().getContents()) {
                if (content != null && !content.getType().isAir()) {
                    return true;
                }
            }
            return false;
        }
        if (meta instanceof BundleMeta bundle) {
            return !bundle.getItems().isEmpty();
        }
        return false;
    }

    public static String itemId(Material material) {
        return material.getKey().getKey().toLowerCase(Locale.ROOT);
    }
}
