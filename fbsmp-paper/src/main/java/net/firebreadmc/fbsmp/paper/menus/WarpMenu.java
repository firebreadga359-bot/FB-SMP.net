package net.firebreadmc.fbsmp.paper.menus;

import net.firebreadmc.fbsmp.paper.FbsmpPlugin;
import net.firebreadmc.fbsmp.paper.data.FbsmpDatabase;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public final class WarpMenu implements Listener {
    private final FbsmpPlugin plugin;
    private final FbsmpDatabase database;

    public WarpMenu(FbsmpPlugin plugin, FbsmpDatabase database) {
        this.plugin = plugin;
        this.database = database;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        UUID playerId = player.getUniqueId();
        Inventory loading = Bukkit.createInventory(null, 27, ChatColor.GOLD + "Loading public warps");
        player.openInventory(loading);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<FbsmpDatabase.SavedLocation> warps = null;
            try {
                warps = database.warps(45);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Could not load public warps: " + exception.getMessage());
            }
            List<FbsmpDatabase.SavedLocation> results = warps;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(playerId);
                if (online == null || online.getOpenInventory().getTopInventory() != loading) {
                    return;
                }
                if (results == null) {
                    online.closeInventory();
                    online.sendMessage(ChatColor.RED + "Public warps could not be loaded.");
                    return;
                }
                WarpHolder holder = new WarpHolder(playerId, UUID.randomUUID(), results);
                Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.GOLD + "Public warps");
                holder.inventory = inventory;
                for (int i = 0; i < holder.warps.size(); i++) {
                    FbsmpDatabase.SavedLocation warp = holder.warps.get(i);
                    inventory.setItem(i, button(Material.ENDER_PEARL, ChatColor.YELLOW + warp.name(),
                            "World: " + warp.world(),
                            "Click to travel; unsafe destinations are blocked."));
                }
                if (holder.warps.isEmpty()) {
                    inventory.setItem(22, button(Material.BARRIER, ChatColor.RED + "No public warps",
                            "An operator can add one with /fbsmp warp create <name>."));
                }
                online.openInventory(inventory);
            });
        });
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof WarpHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                || event.getRawSlot() < 0 || event.getRawSlot() >= holder.warps.size()) {
            return;
        }
        FbsmpDatabase.SavedLocation saved = holder.warps.get(event.getRawSlot());
        World world = Bukkit.getWorld(saved.world());
        if (world == null) {
            player.sendMessage(ChatColor.RED + "That warp's world is not loaded.");
            return;
        }
        Location location = new Location(world, saved.x(), saved.y(), saved.z(), saved.yaw(), saved.pitch());
        if (!safe(location)) {
            player.sendMessage(ChatColor.RED + "That warp is no longer safe.");
            return;
        }
        player.closeInventory();
        player.teleport(location);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof WarpHolder) {
            event.setCancelled(true);
        }
    }

    private static boolean safe(Location location) {
        if (location.getWorld() == null) {
            return false;
        }
        Block floor = location.clone().subtract(0, 1, 0).getBlock();
        Material type = floor.getType();
        return type.isSolid() && type != Material.LAVA && type != Material.MAGMA_BLOCK
                && type != Material.CAMPFIRE && type != Material.SOUL_CAMPFIRE
                && type != Material.CACTUS && type != Material.POWDER_SNOW
                && location.getBlock().isPassable() && location.clone().add(0, 1, 0).getBlock().isPassable();
    }

    private static ItemStack button(Material material, String title, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(title);
        meta.setLore(java.util.Arrays.stream(loreLines).map(line -> ChatColor.GRAY + line).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static final class WarpHolder implements InventoryHolder {
        private final UUID owner;
        @SuppressWarnings("unused")
        private final UUID sessionId;
        private final List<FbsmpDatabase.SavedLocation> warps;
        private Inventory inventory;

        private WarpHolder(UUID owner, UUID sessionId, List<FbsmpDatabase.SavedLocation> warps) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.warps = List.copyOf(warps);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}