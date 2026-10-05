package net.firebreadmc.fbsmp.paper.menus;

import net.firebreadmc.fbsmp.paper.FbsmpPlugin;
import net.firebreadmc.fbsmp.paper.data.FbsmpDatabase;
import net.firebreadmc.fbsmp.paper.listeners.PlayerListener;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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
import java.util.Map;
import java.util.List;
import java.util.UUID;

public final class SettingsMenu implements Listener {
    private static final List<String> SETTINGS = List.of("scoreboard", "notifications", "sounds");
    private static final Map<String, Material> ICONS = Map.of(
            "scoreboard", Material.OAK_SIGN,
            "notifications", Material.BELL,
            "sounds", Material.NOTE_BLOCK
    );
    private final FbsmpPlugin plugin;
    private final FbsmpDatabase database;
    private final PlayerListener playerListener;

    public SettingsMenu(FbsmpPlugin plugin, FbsmpDatabase database, PlayerListener playerListener) {
        this.plugin = plugin;
        this.database = database;
        this.playerListener = playerListener;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        UUID playerId = player.getUniqueId();
        Inventory loading = Bukkit.createInventory(null, 9, ChatColor.GOLD + "Loading settings");
        player.openInventory(loading);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Boolean> values;
            try {
                values = Map.of(
                        "scoreboard", Boolean.parseBoolean(database.setting(playerId, "scoreboard", "true")),
                        "notifications", Boolean.parseBoolean(database.setting(playerId, "notifications", "true")),
                        "sounds", Boolean.parseBoolean(database.setting(playerId, "sounds", "true"))
                );
            } catch (SQLException exception) {
                plugin.getLogger().warning("Could not load settings for " + playerId + ": " + exception.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(playerId);
                    if (online != null && online.getOpenInventory().getTopInventory() == loading) {
                        online.closeInventory();
                        online.sendMessage(ChatColor.RED + "Settings could not be loaded from the database.");
                    }
                });
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(playerId);
                if (online == null || online.getOpenInventory().getTopInventory() != loading) {
                    return;
                }
                SettingsHolder holder = new SettingsHolder(playerId, UUID.randomUUID(), values);
                Inventory inventory = Bukkit.createInventory(holder, 27, ChatColor.GOLD + "Player settings");
                holder.inventory = inventory;
                int[] slots = {11, 13, 15};
                int index = 0;
                for (String key : SETTINGS) {
                    boolean enabled = values.getOrDefault(key, true);
                    inventory.setItem(slots[index++], button(ICONS.get(key),
                            (enabled ? ChatColor.GREEN : ChatColor.RED) + display(key),
                            "Currently " + (enabled ? "enabled" : "disabled"),
                            "Click to " + (enabled ? "disable" : "enable") + "."));
                }
                inventory.setItem(22, button(Material.PAPER, ChatColor.YELLOW + "Saved to your profile",
                        "These settings persist across restarts."));
                online.openInventory(inventory);
            });
        });
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SettingsHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.owner.equals(player.getUniqueId())
                || event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        String setting = switch (event.getRawSlot()) {
            case 11 -> "scoreboard";
            case 13 -> "notifications";
            case 15 -> "sounds";
            default -> null;
        };
        if (setting == null) {
            return;
        }
        boolean value = !holder.values.getOrDefault(setting, true);
        playerListener.setPreference(player, setting, value, saved -> {
            if (!saved) {
                player.sendMessage(ChatColor.RED + "That setting could not be saved.");
                return;
            }
            player.sendMessage(ChatColor.GREEN + display(setting) + " " + (value ? "enabled." : "disabled."));
            open(player);
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SettingsHolder) {
            event.setCancelled(true);
        }
    }

    private static String display(String key) {
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    private static ItemStack button(Material material, String title, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(title);
        meta.setLore(java.util.Arrays.stream(loreLines).map(line -> ChatColor.GRAY + line).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static final class SettingsHolder implements InventoryHolder {
        private final UUID owner;
        @SuppressWarnings("unused")
        private final UUID sessionId;
        private final Map<String, Boolean> values;
        private Inventory inventory;

        private SettingsHolder(UUID owner, UUID sessionId, Map<String, Boolean> values) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.values = Map.copyOf(values);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}