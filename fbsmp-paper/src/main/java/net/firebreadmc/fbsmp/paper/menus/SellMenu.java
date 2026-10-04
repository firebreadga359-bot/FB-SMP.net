package net.firebreadmc.fbsmp.paper.menus;

import net.firebreadmc.fbsmp.common.Money;
import net.firebreadmc.fbsmp.paper.FbsmpPlugin;
import net.firebreadmc.fbsmp.paper.data.FbsmpDatabase;
import net.firebreadmc.fbsmp.paper.listeners.PlayerListener;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public final class SellMenu implements Listener {
    private static final int MENU_SIZE = 54;
    private static final int CANCEL_SLOT = 45;
    private static final int CONFIRM_SLOT = 49;
    private static final int TOTAL_SLOT = 53;
    private final FbsmpPlugin plugin;
    private final FbsmpDatabase database;
    private final PlayerListener playerListener;
    private final NamespacedKey sellItemKey;
    private final Map<UUID, SellSession> active = new ConcurrentHashMap<>();

    public SellMenu(FbsmpPlugin plugin, FbsmpDatabase database, PlayerListener playerListener) {
        this.plugin = plugin;
        this.database = database;
        this.playerListener = playerListener;
        this.sellItemKey = new NamespacedKey(plugin, "sell_line_id");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        SellSession existing = active.get(player.getUniqueId());
        if (existing != null) {
            player.openInventory(existing.inventory);
            return;
        }
        try {
            String id = database.beginSellSession(player.getUniqueId(), System.currentTimeMillis());
            SellHolder holder = new SellHolder(id);
            Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE, ChatColor.GOLD + "Sell inventory");
            holder.inventory = inventory;
            SellSession session = new SellSession(id, inventory);
            active.put(player.getUniqueId(), session);
            refresh(player, session);
            player.openInventory(inventory);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not open sell inventory for " + player.getUniqueId() + ": "
                    + exception.getMessage());
            player.sendMessage(ChatColor.RED + "Sell inventory could not be opened. Try again.");
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SellHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        SellSession session = active.get(player.getUniqueId());
        if (session == null || !session.id.equals(holder.sessionId)) {
            return;
        }
        if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT
                || event.getClick() == ClickType.NUMBER_KEY || event.getClick() == ClickType.DOUBLE_CLICK
                || event.getClick() == ClickType.SWAP_OFFHAND || event.getClick() == ClickType.DROP
                || event.getClick() == ClickType.CONTROL_DROP || event.getClick() == ClickType.CREATIVE) {
            return;
        }
        if (event.getCursor() != null && !event.getCursor().getType().isAir()) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0) {
            return;
        }
        if (rawSlot < MENU_SIZE) {
            if (rawSlot == CANCEL_SLOT) {
                cancel(player, session, true);
            } else if (rawSlot == CONFIRM_SLOT) {
                confirm(player, session);
            }
            return;
        }
        if (event.getClickedInventory() != player.getInventory()
                || event.getClick() != ClickType.LEFT) {
            return;
        }
        int inventorySlot = event.getSlot();
        Bukkit.getScheduler().runTask(plugin, () -> stageHeldStack(player, session, inventorySlot));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SellHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof SellHolder holder)
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        SellSession session = active.get(player.getUniqueId());
        if (session == null || !session.id.equals(holder.sessionId) || session.settling) {
            return;
        }
        cancel(player, session, false);
    }

    private void stageHeldStack(Player player, SellSession session, int slot) {
        if (!player.isOnline() || active.get(player.getUniqueId()) != session) {
            return;
        }
        ItemStack original = player.getInventory().getItem(slot);
        if (original == null || original.getType().isAir()) {
            return;
        }
        try {
            if (database.sellLines(session.id, player.getUniqueId()).size() >= 45) {
                player.sendMessage(ChatColor.RED + "The sell inventory is full.");
                return;
            }
        } catch (SQLException exception) {
            player.sendMessage(ChatColor.RED + "Could not verify sell inventory capacity.");
            return;
        }
        long unitPrice;
        try {
            unitPrice = priceFor(original.getType());
        } catch (IllegalArgumentException exception) {
            player.sendMessage(ChatColor.RED + exception.getMessage());
            return;
        }
        ItemStack stored = original.clone();
        int quantity = stored.getAmount();
        long total;
        try {
            total = Math.multiplyExact(unitPrice, quantity);
        } catch (ArithmeticException exception) {
            player.sendMessage(ChatColor.RED + "That sell total is outside the supported range.");
            return;
        }
        String lineId = UUID.randomUUID().toString();
        ItemStack tagged = original.clone();
        ItemMeta taggedMeta = tagged.getItemMeta();
        if (taggedMeta == null) {
            player.sendMessage(ChatColor.RED + "This item cannot be safely staged for sale.");
            return;
        }
        taggedMeta.getPersistentDataContainer().set(sellItemKey, PersistentDataType.STRING, lineId);
        tagged.setItemMeta(taggedMeta);
        player.getInventory().setItem(slot, tagged);
        player.saveData();

        ItemMeta storedMeta = stored.getItemMeta();
        if (storedMeta != null) {
            storedMeta.getPersistentDataContainer().remove(sellItemKey);
            stored.setItemMeta(storedMeta);
        }
        String itemData = Base64.getEncoder().encodeToString(stored.serializeAsBytes());
        try {
            database.addSellItem(session.id, player.getUniqueId(), lineId, itemData, total,
                    System.currentTimeMillis());
            player.getInventory().setItem(slot, null);
            player.saveData();
            playerListener.markActive(player);
            refresh(player, session);
        } catch (SQLException exception) {
            ItemStack restored = player.getInventory().getItem(slot);
            if (restored != null && restored.hasItemMeta()) {
                ItemMeta meta = restored.getItemMeta();
                meta.getPersistentDataContainer().remove(sellItemKey);
                restored.setItemMeta(meta);
                player.getInventory().setItem(slot, restored);
                player.saveData();
            }
            plugin.getLogger().warning("Could not stage a sell item for " + player.getUniqueId() + ": "
                    + exception.getMessage());
            player.sendMessage(ChatColor.RED + "That item could not be staged. It was left in your inventory.");
        }
    }

    private void refresh(Player player, SellSession session) {
        try {
            Inventory inventory = session.inventory;
            for (int slot = 0; slot < 45; slot++) {
                inventory.setItem(slot, null);
            }
            List<FbsmpDatabase.SellLine> lines = database.sellLines(session.id, player.getUniqueId());
            long total = 0;
            for (int i = 0; i < Math.min(45, lines.size()); i++) {
                FbsmpDatabase.SellLine line = lines.get(i);
                ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(line.itemData()));
                total = Math.addExact(total, line.valueMinor());
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setLore(List.of(ChatColor.GRAY + "Sale value: " + ChatColor.GOLD
                            + Money.format(line.valueMinor())));
                    item.setItemMeta(meta);
                }
                inventory.setItem(i, item);
            }
            inventory.setItem(CANCEL_SLOT, button(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "Cancel sale",
                    "Return staged items to your mailbox."));
            inventory.setItem(CONFIRM_SLOT, button(Material.EMERALD, ChatColor.GREEN + "Confirm sale",
                    "Complete the sale and receive " + Money.format(total) + "."));
            inventory.setItem(TOTAL_SLOT, button(Material.PAPER, ChatColor.GOLD + "Sale total: " + Money.format(total),
                    "Place sellable items from your inventory above."));
        } catch (SQLException | IllegalArgumentException | ArithmeticException exception) {
            plugin.getLogger().warning("Could not refresh sell inventory: " + exception.getMessage());
            player.sendMessage(ChatColor.RED + "Sell inventory could not be refreshed.");
        }
    }

    private void confirm(Player player, SellSession session) {
        try {
            if (database.sellLines(session.id, player.getUniqueId()).isEmpty()) {
                player.sendMessage(ChatColor.YELLOW + "Add an item before confirming.");
                cancel(player, session, true);
                return;
            }
            long total = database.prepareSellCommit(session.id, player.getUniqueId());
            if (total < 0) {
                player.sendMessage(ChatColor.RED + "This sell session is no longer open.");
                return;
            }
            session.settling = true;
            boolean completed = database.finishSellCommit(session.id, player.getUniqueId(), System.currentTimeMillis());
            active.remove(player.getUniqueId());
            player.closeInventory();
            if (completed) {
                player.sendMessage(ChatColor.GREEN + "Sold your items for " + ChatColor.GOLD + Money.format(total) + ".");
            } else {
                player.sendMessage(ChatColor.RED + "The sale could not be settled. Please reconnect to recover it.");
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not settle sell session " + session.id + ": " + exception.getMessage());
            player.sendMessage(ChatColor.RED + "The sale could not be settled. The item escrow is retained for recovery.");
        }
    }

    private void cancel(Player player, SellSession session, boolean close) {
        session.settling = true;
        try {
            database.refundSellSession(session.id, player.getUniqueId(), System.currentTimeMillis());
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not refund sell session " + session.id + ": " + exception.getMessage());
            player.sendMessage(ChatColor.RED + "Items remain in the sell escrow and will be recovered after reconnect.");
        }
        active.remove(player.getUniqueId());
        if (close && player.isOnline()) {
            player.closeInventory();
        }
        if (player.isOnline()) {
            playerListener.deliverMailbox(player);
        }
    }

    private long priceFor(Material material) {
        var file = plugin.getDataFolder().toPath().resolve("sell_prices.yml").toFile();
        if (!file.isFile()) {
            throw new IllegalArgumentException("Sell prices are not configured.");
        }
        YamlConfiguration prices = YamlConfiguration.loadConfiguration(file);
        String raw = prices.getString("prices." + material.name());
        if (raw == null) {
            throw new IllegalArgumentException(material.name() + " is not accepted by the sell inventory.");
        }
        try {
            return new BigDecimal(raw).setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("The configured price for " + material.name() + " is invalid.");
        }
    }

    private static ItemStack button(Material material, String title, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(title);
        meta.setLore(List.of(ChatColor.GRAY + lore));
        item.setItemMeta(meta);
        return item;
    }

    private static final class SellSession {
        private final String id;
        private final Inventory inventory;
        private boolean settling;

        private SellSession(String id, Inventory inventory) {
            this.id = id;
            this.inventory = inventory;
        }
    }

    private static final class SellHolder implements InventoryHolder {
        private final String sessionId;
        private Inventory inventory;

        private SellHolder(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}