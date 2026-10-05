package net.firebreadmc.fbsmp.paper.menus;

import net.firebreadmc.fbsmp.common.Money;
import net.firebreadmc.fbsmp.paper.FbsmpPlugin;
import net.firebreadmc.fbsmp.paper.data.FbsmpDatabase;
import net.firebreadmc.fbsmp.paper.listeners.PlayerListener;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public final class AuctionMenu implements Listener {
    private static final int PAGE_SIZE = 45;
    private final FbsmpPlugin plugin;
    private final FbsmpDatabase database;
    private final PlayerListener playerListener;

    public AuctionMenu(FbsmpPlugin plugin, FbsmpDatabase database, PlayerListener playerListener) {
        this.plugin = plugin;
        this.database = database;
        this.playerListener = playerListener;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        openPage(player, 0);
    }

    private void openPage(Player player, int page) {
        UUID playerId = player.getUniqueId();
        Inventory loading = Bukkit.createInventory(null, 9, ChatColor.GOLD + "Loading auction listings");
        player.openInventory(loading);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<FbsmpDatabase.AuctionListing> listings;
            try {
                listings = database.openAuctions(System.currentTimeMillis(), PAGE_SIZE + 1, page * PAGE_SIZE);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Could not load auction page: " + exception.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(playerId);
                    if (online != null && online.getOpenInventory().getTopInventory() == loading) {
                        online.closeInventory();
                        online.sendMessage(ChatColor.RED + "Auction listings could not be loaded.");
                    }
                });
                return;
            }
            boolean hasNext = listings.size() > PAGE_SIZE;
            List<FbsmpDatabase.AuctionListing> visible = listings.stream().limit(PAGE_SIZE).toList();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(playerId);
                if (online == null || online.getOpenInventory().getTopInventory() != loading) {
                    return;
                }
                ListingHolder holder = new ListingHolder(playerId, UUID.randomUUID(), page, visible, hasNext);
                Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.GOLD + "Auction house · Page "
                        + (page + 1));
                holder.inventory = inventory;
                for (int i = 0; i < visible.size(); i++) {
                    FbsmpDatabase.AuctionListing listing = visible.get(i);
                    ItemStack icon;
                    try {
                        icon = ItemStack.deserializeBytes(Base64.getDecoder().decode(listing.itemData()));
                    } catch (IllegalArgumentException exception) {
                        icon = new ItemStack(Material.BARRIER);
                    }
                    icon = icon.clone();
                    ItemMeta meta = icon.getItemMeta();
                    if (meta != null) {
                        meta.setDisplayName(ChatColor.WHITE + icon.getType().name() + " × " + listing.quantity());
                        List<String> lore = new ArrayList<>();
                        lore.add(ChatColor.GOLD + Money.format(listing.priceMinor()));
                        lore.add(ChatColor.GRAY + "Seller: " + listing.seller().toString().substring(0, 8));
                        lore.add(ChatColor.GRAY + "Listing #" + listing.id());
                        lore.add(ChatColor.YELLOW + "Left-click: review purchase");
                        lore.add(ChatColor.GRAY + "Right-click your own listing: cancel");
                        meta.setLore(lore);
                        icon.setItemMeta(meta);
                    }
                    inventory.setItem(i, icon);
                }
                inventory.setItem(45, button(Material.ARROW, ChatColor.YELLOW + "Previous page",
                        page == 0 ? "Already on the first page." : "Open the previous page."));
                inventory.setItem(49, button(Material.CLOCK, ChatColor.YELLOW + "Refresh", "Reload current listings."));
                inventory.setItem(53, button(Material.ARROW, ChatColor.YELLOW + "Next page",
                        hasNext ? "Open the next page." : "No more listings."));
                if (visible.isEmpty()) {
                    inventory.setItem(22, button(Material.BARRIER, ChatColor.RED + "No active listings",
                            "Create one with /ausell <price> while holding an item."));
                }
                online.openInventory(inventory);
            });
        });
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder rawHolder = event.getView().getTopInventory().getHolder();
        if (!(rawHolder instanceof ListingHolder) && !(rawHolder instanceof ConfirmHolder)) {
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
        if (rawHolder instanceof ListingHolder holder) {
            if (!holder.owner.equals(player.getUniqueId())) {
                return;
            }
            if (slot == 45 && holder.page > 0) {
                openPage(player, holder.page - 1);
            } else if (slot == 49) {
                openPage(player, holder.page);
            } else if (slot == 53 && holder.hasNext) {
                openPage(player, holder.page + 1);
            } else if (slot < holder.listings.size()) {
                FbsmpDatabase.AuctionListing listing = holder.listings.get(slot);
                if (event.getClick() == ClickType.RIGHT && listing.seller().equals(player.getUniqueId())) {
                    cancel(player, listing.id(), holder.page);
                } else if (event.getClick() == ClickType.LEFT && !listing.seller().equals(player.getUniqueId())) {
                    openConfirmation(player, listing, holder.page);
                }
            }
        } else {
            ConfirmHolder holder = (ConfirmHolder) rawHolder;
            if (!holder.owner.equals(player.getUniqueId())) {
                return;
            }
            if (slot == 18) {
                openPage(player, holder.page);
            } else if (slot == 22) {
                purchase(player, holder.listing.id(), holder.page);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof ListingHolder || holder instanceof ConfirmHolder) {
            event.setCancelled(true);
        }
    }

    private void openConfirmation(Player player, FbsmpDatabase.AuctionListing listing, int page) {
        ConfirmHolder holder = new ConfirmHolder(player.getUniqueId(), UUID.randomUUID(), listing, page);
        Inventory inventory = Bukkit.createInventory(holder, 27, ChatColor.GOLD + "Confirm auction purchase");
        holder.inventory = inventory;
        ItemStack item;
        try {
            item = ItemStack.deserializeBytes(Base64.getDecoder().decode(listing.itemData()));
        } catch (IllegalArgumentException exception) {
            item = new ItemStack(Material.BARRIER);
        }
        inventory.setItem(13, button(item.getType(), ChatColor.WHITE + item.getType().name() + " × "
                + listing.quantity(), "Price: " + Money.format(listing.priceMinor()),
                "Your balance is checked again before purchase."));
        inventory.setItem(18, button(Material.REDSTONE, ChatColor.RED + "Cancel", "Return to auction listings."));
        inventory.setItem(22, button(Material.EMERALD, ChatColor.GREEN + "Confirm purchase",
                "Items are delivered through the player mailbox."));
        player.openInventory(inventory);
    }

    private void purchase(Player player, long listingId, int page) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            FbsmpDatabase.AuctionPurchase purchase = null;
            try {
                purchase = database.purchaseAuction(playerId, listingId, System.currentTimeMillis());
            } catch (SQLException exception) {
                plugin.getLogger().warning("Auction purchase failed for " + playerId + ": "
                        + exception.getMessage());
            }
            FbsmpDatabase.AuctionPurchase result = purchase;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(playerId);
                if (online == null) {
                    return;
                }
                if (result == null) {
                    online.sendMessage(ChatColor.RED + "That listing is no longer available or you cannot afford it.");
                } else {
                    playerListener.deliverMailbox(online);
                    online.sendMessage(ChatColor.GREEN + "Purchased the listing for "
                            + Money.format(result.priceMinor()) + ".");
                }
                openPage(online, page);
            });
        });
    }

    private void cancel(Player player, long listingId, int page) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean cancelled = false;
            try {
                cancelled = database.cancelAuction(playerId, listingId, System.currentTimeMillis());
            } catch (SQLException exception) {
                plugin.getLogger().warning("Auction cancellation failed for " + playerId + ": "
                        + exception.getMessage());
            }
            boolean success = cancelled;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(playerId);
                if (online == null) {
                    return;
                }
                if (success) {
                    playerListener.deliverMailbox(online);
                    online.sendMessage(ChatColor.GREEN + "Listing cancelled; the item was returned by player mail.");
                } else {
                    online.sendMessage(ChatColor.RED + "That listing could not be cancelled.");
                }
                openPage(online, page);
            });
        });
    }

    private static ItemStack button(Material material, String title, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(title);
        meta.setLore(java.util.Arrays.stream(loreLines).map(line -> ChatColor.GRAY + line).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static final class ListingHolder implements InventoryHolder {
        private final UUID owner;
        @SuppressWarnings("unused")
        private final UUID sessionId;
        private final int page;
        private final List<FbsmpDatabase.AuctionListing> listings;
        private final boolean hasNext;
        private Inventory inventory;

        private ListingHolder(UUID owner, UUID sessionId, int page,
                              List<FbsmpDatabase.AuctionListing> listings, boolean hasNext) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.page = page;
            this.listings = List.copyOf(listings);
            this.hasNext = hasNext;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class ConfirmHolder implements InventoryHolder {
        private final UUID owner;
        @SuppressWarnings("unused")
        private final UUID sessionId;
        private final FbsmpDatabase.AuctionListing listing;
        private final int page;
        private Inventory inventory;

        private ConfirmHolder(UUID owner, UUID sessionId, FbsmpDatabase.AuctionListing listing, int page) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.listing = listing;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}