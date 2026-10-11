package net.firebreadmc.fbsmp.paper.menus;

import net.firebreadmc.fbsmp.common.Money;
import net.firebreadmc.fbsmp.common.sell.SellEligibilityPolicy;
import net.firebreadmc.fbsmp.common.sell.SellPreview;
import net.firebreadmc.fbsmp.paper.FbsmpPlugin;
import net.firebreadmc.fbsmp.paper.data.FbsmpDatabase;
import net.firebreadmc.fbsmp.paper.listeners.PlayerListener;
import net.firebreadmc.fbsmp.paper.sell.SellService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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

import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public final class SellMenu implements Listener {
    private static final int MENU_SIZE = 54;
    private static final int CANCEL_SLOT = 45;
    private static final int SELL_ALL_SLOT = 48;
    private static final int CONFIRM_SLOT = 49;
    private static final int TOTAL_SLOT = 53;
    private final FbsmpPlugin plugin;
    private final FbsmpDatabase database;
    private final PlayerListener playerListener;
    private final SellService sellService;
    private final NamespacedKey sellItemKey;
    private final Map<UUID, SellSession> active = new ConcurrentHashMap<>();

    public SellMenu(FbsmpPlugin plugin, FbsmpDatabase database, PlayerListener playerListener,
                    SellService sellService) {
        this.plugin = plugin;
        this.database = database;
        this.playerListener = playerListener;
        this.sellService = sellService;
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
            } else if (rawSlot == SELL_ALL_SLOT) {
                stageSellAll(player, session);
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
        SellService.Offer offer;
        try {
            offer = sellService.offer(slot, original);
        } catch (RuntimeException exception) {
            player.sendMessage(ChatColor.RED + "That item could not be priced for sale.");
            return;
        }
        if (offer == null) {
            SellEligibilityPolicy.Decision decision = sellService.evaluate(original);
            player.sendMessage(ChatColor.RED + (decision.message().isBlank()
                    ? "That item cannot be sold." : decision.message()));
            return;
        }
        long total = offer.totalValueMinor();
        ItemStack stored = original.clone();
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
            inventory.setItem(SELL_ALL_SLOT, button(Material.HOPPER, ChatColor.AQUA + "Sell all sellables",
                    "Stage every sellable stack from your inventory."));
            inventory.setItem(CONFIRM_SLOT, button(Material.EMERALD, ChatColor.GREEN + "Confirm sale",
                    "Complete the sale and receive " + Money.format(total) + "."));
            inventory.setItem(TOTAL_SLOT, button(Material.PAPER, ChatColor.GOLD + "Sale total: " + Money.format(total),
                    "Left-click inventory items, or use Sell all."));
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

    private void stageSellAll(Player player, SellSession session) {
        int capacity;
        try {
            capacity = 45 - database.sellLines(session.id, player.getUniqueId()).size();
        } catch (SQLException exception) {
            player.sendMessage(ChatColor.RED + "Could not verify sell inventory capacity.");
            return;
        }
        if (capacity <= 0) {
            player.sendMessage(ChatColor.RED + "The sell inventory is full.");
            return;
        }
        List<SellService.Offer> offers = sellService.offers(player);
        int staged = 0;
        long stagedValue = 0;
        for (SellService.Offer offer : offers) {
            if (staged >= capacity) {
                break;
            }
            if (stage(player, session, offer)) {
                staged++;
                stagedValue += offer.totalValueMinor();
            }
        }
        playerListener.markActive(player);
        refresh(player, session);
        if (staged == 0) {
            player.sendMessage(ChatColor.YELLOW + "Nothing in your inventory can be sold right now.");
        } else {
            player.sendMessage(ChatColor.GREEN + "Staged " + staged + " stack(s) worth "
                    + ChatColor.GOLD + Money.format(stagedValue) + ChatColor.GREEN + ".");
        }
        showSellPreview(player);
    }

    private void showSellPreview(Player player) {
        SellPreview preview = sellService.previewInventory(player);
        if (preview.empty()) {
            player.sendMessage(ChatColor.YELLOW + "No sellable items were found in your inventory.");
        } else {
            player.sendMessage(ChatColor.GOLD + "Sellable now: " + ChatColor.WHITE + preview.soldItems()
                    + " item(s) in " + preview.soldTypes() + " type(s) for " + ChatColor.GOLD
                    + Money.format(preview.totalMinor()) + ChatColor.WHITE + ".");
        }
        int shown = 0;
        for (SellPreview.Kept kept : preview.kept()) {
            if (shown++ >= 5) {
                player.sendMessage(ChatColor.GRAY + "  ...and " + (preview.kept().size() - 5)
                        + " more item type(s) kept.");
                break;
            }
            player.sendMessage(ChatColor.GRAY + "  Kept " + kept.itemId() + " x" + kept.quantity()
                    + ": " + kept.message());
        }
    }

    /**
     * Stages one eligible offer into the escrow and clears the source slot. Returns
     * true only when the item was stored. On failure the slot is left untouched.
     */
    private boolean stage(Player player, SellSession session, SellService.Offer offer) {
        int slot = offer.slot();
        ItemStack current = player.getInventory().getItem(slot);
        if (current == null || !current.isSimilar(offer.stack())) {
            return false;
        }
        String lineId = UUID.randomUUID().toString();
        ItemStack tagged = current.clone();
        ItemMeta taggedMeta = tagged.getItemMeta();
        if (taggedMeta == null) {
            return false;
        }
        taggedMeta.getPersistentDataContainer().set(sellItemKey, PersistentDataType.STRING, lineId);
        tagged.setItemMeta(taggedMeta);
        player.getInventory().setItem(slot, tagged);
        player.saveData();

        ItemStack stored = offer.stack().clone();
        ItemMeta storedMeta = stored.getItemMeta();
        if (storedMeta != null) {
            storedMeta.getPersistentDataContainer().remove(sellItemKey);
            stored.setItemMeta(storedMeta);
        }
        String itemData = Base64.getEncoder().encodeToString(stored.serializeAsBytes());
        try {
            database.addSellItem(session.id, player.getUniqueId(), lineId, itemData,
                    offer.totalValueMinor(), System.currentTimeMillis());
            player.getInventory().setItem(slot, null);
            player.saveData();
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not stage a sell-all item for " + player.getUniqueId() + ": "
                    + exception.getMessage());
            return false;
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