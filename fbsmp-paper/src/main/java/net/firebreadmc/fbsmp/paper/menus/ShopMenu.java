package net.firebreadmc.fbsmp.paper.menus;

import net.firebreadmc.fbsmp.common.Money;
import net.firebreadmc.fbsmp.paper.FbsmpPlugin;
import net.firebreadmc.fbsmp.paper.data.FbsmpDatabase;
import net.firebreadmc.fbsmp.paper.listeners.PlayerListener;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ShopMenu implements Listener {
    private final FbsmpPlugin plugin;
    private final FbsmpDatabase database;
    private final PlayerListener playerListener;

    public ShopMenu(FbsmpPlugin plugin, FbsmpDatabase database, PlayerListener playerListener) {
        this.plugin = plugin;
        this.database = database;
        this.playerListener = playerListener;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void openCategories(Player player) {
        List<Category> categories = loadCategories();
        CategoryHolder holder = new CategoryHolder(player.getUniqueId(), UUID.randomUUID(), categories);
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.GOLD + "FB-SMP Shop");
        holder.inventory = inventory;
        for (int i = 0; i < Math.min(45, categories.size()); i++) {
            Category category = categories.get(i);
            Material icon = category.materials.stream().findFirst().orElse(Material.CHEST);
            inventory.setItem(i, button(icon, ChatColor.GOLD + category.name,
                    "Browse " + category.materials.size() + " configured item(s)."));
        }
        inventory.setItem(49, button(Material.PAPER, ChatColor.YELLOW + "Safe item delivery",
                "Purchases are held in player mail until your inventory has room."));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder topHolder = event.getView().getTopInventory().getHolder();
        if (!(topHolder instanceof CategoryHolder) && !(topHolder instanceof ItemsHolder)
                && !(topHolder instanceof QuantityHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        if (topHolder instanceof CategoryHolder holder) {
            if (!holder.owner.equals(player.getUniqueId()) || slot >= holder.categories.size()) {
                return;
            }
            openCategory(player, holder.categories.get(slot));
        } else if (topHolder instanceof ItemsHolder holder) {
            if (!holder.owner.equals(player.getUniqueId())) {
                return;
            }
            if (slot == 45) {
                openCategories(player);
            } else if (slot < holder.entries.size()) {
                ShopEntry entry = holder.entries.get(slot);
                openQuantity(player, entry);
            }
        } else if (topHolder instanceof QuantityHolder holder) {
            if (!holder.owner.equals(player.getUniqueId())) {
                return;
            }
            if (slot == 10 || slot == 13 || slot == 16) {
                holder.quantity = slot == 10 ? 1 : slot == 13 ? 16 : 64;
                updateQuantity(holder);
            } else if (slot == 18) {
                openCategory(player, holder.entry.category);
            } else if (slot == 25 && holder.quantity > 0) {
                purchase(player, holder.entry, holder.quantity);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof CategoryHolder || holder instanceof ItemsHolder || holder instanceof QuantityHolder) {
            event.setCancelled(true);
        }
    }

    private void openCategory(Player player, Category category) {
        List<ShopEntry> entries = new ArrayList<>();
        for (Material material : category.materials) {
            Long price = category.prices.get(material);
            if (price != null && price > 0) {
                entries.add(new ShopEntry(category, material, price));
            }
        }
        ItemsHolder holder = new ItemsHolder(player.getUniqueId(), UUID.randomUUID(), category, entries);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                ChatColor.GOLD + "Shop / " + category.name);
        holder.inventory = inventory;
        for (int i = 0; i < Math.min(45, entries.size()); i++) {
            ShopEntry entry = entries.get(i);
            inventory.setItem(i, button(entry.material, ChatColor.WHITE + formatName(entry.material.name()),
                    Money.format(entry.unitPriceMinor) + " each",
                    "Left-click to select purchase quantity."));
        }
        inventory.setItem(45, button(Material.ARROW, ChatColor.YELLOW + "Back to categories", "Return to the shop categories."));
        if (entries.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, ChatColor.RED + "No priced items",
                    "Configure item prices in shop.yml."));
        }
        player.openInventory(inventory);
    }

    private void openQuantity(Player player, ShopEntry entry) {
        QuantityHolder holder = new QuantityHolder(player.getUniqueId(), UUID.randomUUID(), entry);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                ChatColor.GOLD + "Buy " + formatName(entry.material.name()));
        holder.inventory = inventory;
        inventory.setItem(4, button(entry.material, ChatColor.WHITE + formatName(entry.material.name()),
                Money.format(entry.unitPriceMinor) + " each"));
        inventory.setItem(10, button(Material.PAPER, ChatColor.YELLOW + "1 item", "Select quantity 1."));
        inventory.setItem(13, button(Material.PAPER, ChatColor.YELLOW + "16 items", "Select quantity 16."));
        inventory.setItem(16, button(Material.PAPER, ChatColor.YELLOW + "64 items", "Select quantity 64."));
        inventory.setItem(18, button(Material.ARROW, ChatColor.YELLOW + "Back", "Return to this category."));
        holder.quantity = 1;
        updateQuantity(holder);
        player.openInventory(inventory);
    }

    private void updateQuantity(QuantityHolder holder) {
        long total;
        try {
            total = Math.multiplyExact(holder.entry.unitPriceMinor, holder.quantity);
        } catch (ArithmeticException exception) {
            holder.quantity = 0;
            holder.inventory.setItem(25, button(Material.BARRIER, ChatColor.RED + "Invalid total",
                    "This purchase exceeds the supported amount."));
            return;
        }
        holder.inventory.setItem(25, button(Material.EMERALD, ChatColor.GREEN + "Confirm purchase",
                holder.quantity + "x " + formatName(holder.entry.material.name()) + " — " + Money.format(total),
                "Purchased items are delivered by player mail."));
    }

    private void purchase(Player player, ShopEntry entry, int quantity) {
        if (quantity < 1 || quantity > 64) {
            player.sendMessage(ChatColor.RED + "Invalid purchase quantity.");
            return;
        }
        long total;
        try {
            total = Math.multiplyExact(entry.unitPriceMinor, quantity);
        } catch (ArithmeticException exception) {
            player.sendMessage(ChatColor.RED + "That purchase exceeds the supported amount.");
            return;
        }
        String data = Base64.getEncoder().encodeToString(
                new ItemStack(entry.material, quantity).serializeAsBytes());
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean purchased = false;
            try {
                purchased = database.debitAndMail(playerId, total, data,
                        quantity + "x " + entry.material.name(), System.currentTimeMillis());
            } catch (SQLException exception) {
                plugin.getLogger().warning("Shop purchase failed for " + playerId + ": " + exception.getMessage());
            }
            boolean success = purchased;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player onlinePlayer = Bukkit.getPlayer(playerId);
                if (onlinePlayer == null) {
                    return;
                }
                onlinePlayer.closeInventory();
                if (success) {
                    onlinePlayer.sendMessage(ChatColor.GREEN + "Purchased " + quantity + "x "
                            + entry.material.name() + " for " + ChatColor.GOLD + Money.format(total)
                            + ChatColor.GREEN + ". Make inventory space to receive player mail.");
                    playerListener.deliverMailbox(onlinePlayer);
                } else {
                    onlinePlayer.sendMessage(ChatColor.RED + "Insufficient funds or the purchase could not be completed.");
                }
            });
        });
    }

    private List<Category> loadCategories() {
        File file = new File(plugin.getDataFolder(), "shop.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection configuredCategories = config.getConfigurationSection("categories");
        ConfigurationSection configuredPrices = config.getConfigurationSection("prices");
        if (configuredCategories == null || configuredPrices == null) {
            plugin.getLogger().warning("Shop categories or prices are missing from shop.yml.");
            return List.of();
        }
        Map<Material, Long> prices = new LinkedHashMap<>();
        for (String itemName : configuredPrices.getKeys(false)) {
            Material material = Material.matchMaterial(itemName.toUpperCase(Locale.ROOT));
            if (material == null || material.isAir()) {
                plugin.getLogger().warning("Ignoring invalid shop material " + itemName + ".");
                continue;
            }
            try {
                prices.put(material, Money.parseMinorUnits(configuredPrices.getString(itemName)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring invalid shop price " + itemName + ".");
            }
        }
        List<Category> categories = new ArrayList<>();
        for (String categoryName : configuredCategories.getKeys(false)) {
            List<Material> materials = new ArrayList<>();
            for (String itemName : configuredCategories.getStringList(categoryName + ".items")) {
                Material material = Material.matchMaterial(itemName.toUpperCase(Locale.ROOT));
                if (material != null && !material.isAir() && prices.containsKey(material)) {
                    materials.add(material);
                }
            }
            categories.add(new Category(categoryName, materials, prices));
        }
        return categories;
    }

    private static ItemStack button(Material material, String title, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(title);
        meta.setLore(java.util.Arrays.stream(loreLines).map(line -> ChatColor.GRAY + line).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static String formatName(String name) {
        String[] words = name.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private record ShopEntry(Category category, Material material, long unitPriceMinor) {}

    private static final class Category {
        private final String name;
        private final List<Material> materials;
        private final Map<Material, Long> prices;

        private Category(String name, List<Material> materials, Map<Material, Long> prices) {
            this.name = name;
            this.materials = List.copyOf(materials);
            this.prices = Map.copyOf(prices);
        }
    }

    private abstract static class MenuHolder implements InventoryHolder {
        final UUID owner;
        @SuppressWarnings("unused")
        private final UUID sessionId;
        Inventory inventory;

        private MenuHolder(UUID owner, UUID sessionId) {
            this.owner = owner;
            this.sessionId = sessionId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class CategoryHolder extends MenuHolder {
        private final List<Category> categories;

        private CategoryHolder(UUID owner, UUID sessionId, List<Category> categories) {
            super(owner, sessionId);
            this.categories = List.copyOf(categories);
        }
    }

    private static final class ItemsHolder extends MenuHolder {
        private final Category category;
        private final List<ShopEntry> entries;

        private ItemsHolder(UUID owner, UUID sessionId, Category category, List<ShopEntry> entries) {
            super(owner, sessionId);
            this.category = category;
            this.entries = List.copyOf(entries);
        }
    }

    private static final class QuantityHolder extends MenuHolder {
        private final ShopEntry entry;
        private int quantity;

        private QuantityHolder(UUID owner, UUID sessionId, ShopEntry entry) {
            super(owner, sessionId);
            this.entry = entry;
        }
    }
}