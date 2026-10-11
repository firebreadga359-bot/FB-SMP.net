package net.firebreadmc.fbsmp.paper.menus;

import net.firebreadmc.fbsmp.common.Money;
import net.firebreadmc.fbsmp.common.shop.ItemCategory;
import net.firebreadmc.fbsmp.common.shop.ShopCatalog;
import net.firebreadmc.fbsmp.paper.FbsmpPlugin;
import net.firebreadmc.fbsmp.paper.data.FbsmpDatabase;
import net.firebreadmc.fbsmp.paper.listeners.PlayerListener;
import net.firebreadmc.fbsmp.paper.shop.ShopPricing;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Catalog-driven shop GUI with categories, pagination and a Bedrock-friendly
 * quantity menu. Every important action is a plain left-click; right-click is an
 * equivalent convenience, never a requirement.
 *
 * <p>Purchases are atomic: the database debits and queues the item in one
 * transaction, and the item is delivered by player mail.
 */
public final class ShopMenu implements Listener {

    private static final int SIZE = 54;
    private static final int PAGE_SIZE = ShopCatalog.PAGE_SIZE;
    private static final int PREV_SLOT = 45;
    private static final int NEXT_SLOT = 53;
    private static final int BACK_SLOT = 49;
    private static final int MAX_QUANTITY = 2304;

    private final FbsmpPlugin plugin;
    private final FbsmpDatabase database;
    private final PlayerListener playerListener;
    private final NamespacedKey guiMarkerKey;
    private ShopPricing.Loaded loaded;

    public ShopMenu(FbsmpPlugin plugin, FbsmpDatabase database, PlayerListener playerListener) {
        this.plugin = plugin;
        this.database = database;
        this.playerListener = playerListener;
        this.guiMarkerKey = new NamespacedKey(plugin, "shop_gui");
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void reload() {
        this.loaded = ShopPricing.load(plugin.getDataFolder(), plugin.getLogger());
    }

    public ShopCatalog catalog() {
        return loaded.catalog();
    }

    /** Buy price in minor units for a namespaced or bare item id, or null if not sellable. */
    public Long buyPrice(String itemId) {
        return loaded.registry().buyPrice(itemId);
    }

    /** Sell price in minor units for a namespaced or bare item id, or null if not buyable. */
    public Long sellPrice(String itemId) {
        return loaded.registry().sellPrice(itemId);
    }

    public boolean isGuiMarker(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(guiMarkerKey, PersistentDataType.BYTE);
    }

    public void openCategories(Player player) {
        List<ItemCategory> categories = loaded.catalog().playerCategories();
        CategoryHolder holder = new CategoryHolder(player.getUniqueId(), categories, 0);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, ChatColor.GOLD + "FB-SMP Shop");
        holder.inventory = inventory;
        paintCategories(holder);
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder top = event.getView().getTopInventory().getHolder();
        if (!(top instanceof CategoryHolder) && !(top instanceof ItemsHolder)
                && !(top instanceof QuantityHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // Bedrock "tap" maps to LEFT; RIGHT is an accepted convenience. All other
        // click types (shift, number-key, double, drop, offhand, creative) are inert.
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) {
            return;
        }
        if (event.getCursor() != null && !event.getCursor().getType().isAir()) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        if (top instanceof CategoryHolder holder) {
            handleCategoryClick(player, holder, slot);
        } else if (top instanceof ItemsHolder holder) {
            handleItemsClick(player, holder, slot);
        } else if (top instanceof QuantityHolder holder) {
            handleQuantityClick(player, holder, slot);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof CategoryHolder || holder instanceof ItemsHolder
                || holder instanceof QuantityHolder) {
            event.setCancelled(true);
        }
    }

    private void handleCategoryClick(Player player, CategoryHolder holder, int slot) {
        if (!holder.owner.equals(player.getUniqueId())) {
            return;
        }
        int index = holder.pageIndex * PAGE_SIZE + slot;
        if (slot < PAGE_SIZE && index < holder.categories.size()) {
            openCategory(player, holder.categories.get(index), 0);
        }
    }

    private void handleItemsClick(Player player, ItemsHolder holder, int slot) {
        if (!holder.owner.equals(player.getUniqueId())) {
            return;
        }
        ShopCatalog.Page page = loaded.catalog().page(holder.category, holder.pageIndex);
        if (slot == PREV_SLOT && page.hasPrevious()) {
            openCategory(player, holder.category, holder.pageIndex - 1);
        } else if (slot == NEXT_SLOT && page.hasNext()) {
            openCategory(player, holder.category, holder.pageIndex + 1);
        } else if (slot == BACK_SLOT) {
            openCategories(player);
        } else if (slot < PAGE_SIZE && slot < page.entries().size()) {
            ShopCatalog.Entry entry = page.entries().get(slot);
            if (entry.buyable()) {
                openQuantity(player, holder.category, entry, holder.pageIndex);
            } else {
                player.sendMessage(ChatColor.RED + "That item is not for sale.");
            }
        }
    }

    private void handleQuantityClick(Player player, QuantityHolder holder, int slot) {
        if (!holder.owner.equals(player.getUniqueId())) {
            return;
        }
        switch (slot) {
            case 10 -> setQuantity(holder, 1);
            case 12 -> setQuantity(holder, Math.max(1, holder.quantity - 1));
            case 13 -> setQuantity(holder, 16);
            case 14 -> setQuantity(holder, holder.quantity + 1);
            case 16 -> setQuantity(holder, 64);
            case 18 -> openCategory(player, holder.category, holder.returnPage);
            case 25 -> purchase(player, holder);
            default -> { }
        }
    }

    private void setQuantity(QuantityHolder holder, int quantity) {
        holder.quantity = Math.max(1, Math.min(MAX_QUANTITY, quantity));
        paintQuantity(holder);
    }

    private void openCategory(Player player, ItemCategory category, int pageIndex) {
        ItemsHolder holder = new ItemsHolder(player.getUniqueId(), category, pageIndex);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                ChatColor.GOLD + "Shop / " + category.displayName());
        holder.inventory = inventory;
        paintItems(holder);
        player.openInventory(inventory);
    }

    private void openQuantity(Player player, ItemCategory category, ShopCatalog.Entry entry, int returnPage) {
        QuantityHolder holder = new QuantityHolder(player.getUniqueId(), category, entry, returnPage);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                ChatColor.GOLD + "Buy " + displayName(entry.itemId()));
        holder.inventory = inventory;
        paintQuantity(holder);
        player.openInventory(inventory);
    }

    private void paintCategories(CategoryHolder holder) {
        Inventory inventory = holder.inventory;
        inventory.clear();
        ShopCatalog catalog = loaded.catalog();
        int start = holder.pageIndex * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = start + i;
            if (index >= holder.categories.size()) {
                break;
            }
            ItemCategory category = holder.categories.get(index);
            Material icon = iconFor(category);
            long buyable = catalog.entries(category).stream().filter(ShopCatalog.Entry::buyable).count();
            inventory.setItem(i, button(icon, ChatColor.GOLD + category.displayName(),
                    buyable + " item(s) available"));
        }
        inventory.setItem(BACK_SLOT, button(Material.PAPER, ChatColor.YELLOW + "Safe delivery",
                "Purchases are held in player mail until you have room."));
    }

    private void paintItems(ItemsHolder holder) {
        Inventory inventory = holder.inventory;
        inventory.clear();
        ShopCatalog.Page page = loaded.catalog().page(holder.category, holder.pageIndex);
        List<ShopCatalog.Entry> entries = page.entries();
        for (int i = 0; i < entries.size() && i < PAGE_SIZE; i++) {
            ShopCatalog.Entry entry = entries.get(i);
            Material material = materialOf(entry.itemId());
            if (material == null) {
                continue;
            }
            List<String> lore = new ArrayList<>();
            if (entry.buyable()) {
                lore.add(Money.format(entry.buyMinor()) + " each");
                lore.add("Left-click to choose a quantity.");
            } else {
                lore.add("Not currently for sale.");
            }
            inventory.setItem(i, button(material, ChatColor.WHITE + displayName(entry.itemId()), lore));
        }
        if (entries.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, ChatColor.RED + "No items",
                    "This category has no priced items yet."));
        }
        if (page.hasPrevious()) {
            inventory.setItem(PREV_SLOT, button(Material.ARROW, ChatColor.YELLOW + "Previous page"));
        }
        if (page.hasNext()) {
            inventory.setItem(NEXT_SLOT, button(Material.ARROW, ChatColor.YELLOW + "Next page"));
        }
        inventory.setItem(BACK_SLOT, button(Material.BARRIER, ChatColor.YELLOW + "Back to categories"));
        inventory.setItem(4, button(Material.PAPER, ChatColor.GOLD + holder.category.displayName(),
                "Page " + (page.pageIndex() + 1) + " of " + page.pageCount()));
    }

    private void paintQuantity(QuantityHolder holder) {
        Inventory inventory = holder.inventory;
        inventory.clear();
        ShopCatalog.Entry entry = holder.entry;
        Material material = materialOf(entry.itemId());
        if (material != null) {
            inventory.setItem(4, button(material, ChatColor.WHITE + displayName(entry.itemId()),
                    Money.format(entry.buyMinor()) + " each"));
        }
        inventory.setItem(10, button(Material.PAPER, ChatColor.YELLOW + "1 item"));
        inventory.setItem(12, button(Material.RED_STAINED_GLASS_PANE, ChatColor.YELLOW + "-1"));
        inventory.setItem(13, button(Material.PAPER, ChatColor.YELLOW + "16 items"));
        inventory.setItem(14, button(Material.LIME_STAINED_GLASS_PANE, ChatColor.YELLOW + "+1"));
        inventory.setItem(16, button(Material.PAPER, ChatColor.YELLOW + "64 items"));
        inventory.setItem(18, button(Material.ARROW, ChatColor.YELLOW + "Back"));

        long total;
        try {
            total = Math.multiplyExact(entry.buyMinor(), holder.quantity);
        } catch (ArithmeticException exception) {
            holder.quantity = 1;
            total = entry.buyMinor();
        }
        inventory.setItem(22, button(Material.PAPER, ChatColor.GOLD + "Quantity: " + holder.quantity));
        inventory.setItem(25, button(Material.EMERALD, ChatColor.GREEN + "Confirm purchase",
                holder.quantity + "x " + displayName(entry.itemId()) + " — " + Money.format(total),
                "Delivered by player mail."));
    }

    private void purchase(Player player, QuantityHolder holder) {
        int quantity = holder.quantity;
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            player.sendMessage(ChatColor.RED + "Invalid purchase quantity.");
            return;
        }
        long total;
        try {
            total = Math.multiplyExact(holder.entry.buyMinor(), quantity);
        } catch (ArithmeticException exception) {
            player.sendMessage(ChatColor.RED + "That purchase exceeds the supported amount.");
            return;
        }
        Material material = materialOf(holder.entry.itemId());
        if (material == null) {
            player.sendMessage(ChatColor.RED + "That item is no longer available.");
            return;
        }
        String data = Base64.getEncoder().encodeToString(
                new ItemStack(material, Math.min(quantity, material.getMaxStackSize())).serializeAsBytes());
        UUID playerId = player.getUniqueId();
        String itemId = holder.entry.itemId();
        player.closeInventory();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean purchased = false;
            try {
                purchased = database.debitAndMail(playerId, total, data,
                        quantity + "x " + itemId, System.currentTimeMillis());
            } catch (SQLException exception) {
                plugin.getLogger().warning("Shop purchase failed for " + playerId + ": " + exception.getMessage());
            }
            boolean success = purchased;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(playerId);
                if (online == null) {
                    return;
                }
                if (success) {
                    online.sendMessage(ChatColor.GREEN + "Purchased " + quantity + "x "
                            + displayName(itemId) + " for " + ChatColor.GOLD + Money.format(total)
                            + ChatColor.GREEN + ".");
                    playerListener.deliverMailbox(online);
                } else {
                    online.sendMessage(ChatColor.RED + "Insufficient funds or the purchase could not be completed.");
                }
            });
        });
    }

    private Material iconFor(ItemCategory category) {
        for (ShopCatalog.Entry entry : loaded.catalog().entries(category)) {
            Material material = materialOf(entry.itemId());
            if (material != null && material.isItem()) {
                return material;
            }
        }
        return Material.CHEST;
    }

    private static Material materialOf(String itemId) {
        return Material.matchMaterial(itemId.toUpperCase(Locale.ROOT));
    }

    private static String displayName(String itemId) {
        String[] words = itemId.split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static ItemStack button(Material material, String title, String... loreLines) {
        return button(material, title, java.util.Arrays.asList(loreLines));
    }

    private static ItemStack button(Material material, String title, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(title);
            if (!loreLines.isEmpty()) {
                meta.setLore(loreLines.stream().map(line -> ChatColor.GRAY + line).toList());
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private abstract static class MenuHolder implements InventoryHolder {
        final UUID owner;
        Inventory inventory;

        MenuHolder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class CategoryHolder extends MenuHolder {
        final List<ItemCategory> categories;
        final int pageIndex;

        CategoryHolder(UUID owner, List<ItemCategory> categories, int pageIndex) {
            super(owner);
            this.categories = List.copyOf(categories);
            this.pageIndex = pageIndex;
        }
    }

    private static final class ItemsHolder extends MenuHolder {
        final ItemCategory category;
        final int pageIndex;

        ItemsHolder(UUID owner, ItemCategory category, int pageIndex) {
            super(owner);
            this.category = category;
            this.pageIndex = pageIndex;
        }
    }

    private static final class QuantityHolder extends MenuHolder {
        final ItemCategory category;
        final ShopCatalog.Entry entry;
        final int returnPage;
        int quantity = 1;

        QuantityHolder(UUID owner, ItemCategory category, ShopCatalog.Entry entry, int returnPage) {
            super(owner);
            this.category = category;
            this.entry = entry;
            this.returnPage = returnPage;
        }
    }
}
