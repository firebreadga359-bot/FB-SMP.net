package net.firebreadmc.fbsmp.paper.listeners;

import net.firebreadmc.fbsmp.paper.FbsmpPlugin;
import net.firebreadmc.fbsmp.paper.data.FbsmpDatabase;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class PlayerListener implements Listener {
    private static final Set<Material> UNSAFE_GROUND = Set.of(
            Material.LAVA, Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.CACTUS, Material.FIRE, Material.SOUL_FIRE, Material.POWDER_SNOW
    );
    private final FbsmpPlugin plugin;
    private final FbsmpDatabase database;
    private final NamespacedKey mailKey;
    private final NamespacedKey sellItemKey;
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sessionStarted = new ConcurrentHashMap<>();
    private final Map<UUID, Activity> pendingActivity = new ConcurrentHashMap<>();
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();

    public PlayerListener(FbsmpPlugin plugin, FbsmpDatabase database) {
        this.plugin = plugin;
        this.database = database;
        this.mailKey = new NamespacedKey(plugin, "mail_id");
        this.sellItemKey = new NamespacedKey(plugin, "sell_line_id");
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkAfk, 20L * 20, 20L * 20);
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushActivity, 20L * 60, 20L * 60);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        lastActivity.put(player.getUniqueId(), now);
        sessionStarted.put(player.getUniqueId(), now);
        pendingActivity.put(player.getUniqueId(), new Activity());
        boolean firstJoin = !player.hasPlayedBefore();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                database.ensurePlayer(player.getUniqueId(), player.getName(), now,
                        plugin.getConfig().getLong("economy.starting-balance", 0));
                if (firstJoin && plugin.getConfig().getBoolean("starter-kit.enabled", true)) {
                    database.claimStarterKit(player.getUniqueId());
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    Set<String> taggedLines = getTaggedSellLines(player);
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            database.recoverSellSessions(player.getUniqueId(), taggedLines, System.currentTimeMillis());
                        } catch (SQLException recoveryException) {
                            plugin.getLogger().severe("Could not recover sell escrow for " + player.getUniqueId()
                                    + ": " + recoveryException.getMessage());
                        }
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            clearSellMarkers(player);
                            if (firstJoin) {
                                findSafeLocation(player.getWorld(), 0, 1600).ifPresent(player::teleport);
                            }
                            deliverMailbox(player);
                        });
                    });
                });
            } catch (SQLException exception) {
                plugin.getLogger().severe("Could not initialize player " + player.getUniqueId() + ": "
                        + exception.getMessage());
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        afk.remove(playerId);
        flushPlayer(playerId);
        lastActivity.remove(playerId);
        sessionStarted.remove(playerId);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return;
        }
        markActive(event.getPlayer());
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        afk.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        pendingActivity.computeIfAbsent(event.getPlayer().getUniqueId(), ignored -> new Activity()).broken++;
        markActive(event.getPlayer());
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        pendingActivity.computeIfAbsent(event.getPlayer().getUniqueId(), ignored -> new Activity()).placed++;
        markActive(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        pendingActivity.computeIfAbsent(event.getEntity().getUniqueId(), ignored -> new Activity()).deaths++;
    }

    public boolean isAfk(UUID playerId) {
        return afk.contains(playerId);
    }

    public void toggleAfk(Player player) {
        if (afk.remove(player.getUniqueId())) {
            markActive(player);
            player.sendMessage("You are no longer AFK.");
        } else {
            afk.add(player.getUniqueId());
            lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
            player.sendMessage("You are now AFK.");
        }
    }

    public void markActive(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        if (afk.remove(player.getUniqueId())) {
            player.sendMessage("You are no longer AFK.");
        }
    }

    public void deliverMailbox(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var mail = database.pendingMail(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    for (FbsmpDatabase.MailItem entry : mail) {
                        try {
                            ItemStack item = ItemStack.deserializeBytes(
                                    java.util.Base64.getDecoder().decode(entry.itemData()));
                            if (hasMailMarker(player, entry.id())) {
                                markMailClaimed(entry.id(), player.getUniqueId());
                                continue;
                            }
                            if (!canFit(player, item)) {
                                player.sendMessage("You have mail waiting. Make inventory space to receive it.");
                                continue;
                            }
                            var meta = item.getItemMeta();
                            if (meta == null) {
                                plugin.getLogger().warning("Mail item has no item metadata; leaving it in the mailbox.");
                                continue;
                            }
                            meta.getPersistentDataContainer().set(mailKey, PersistentDataType.STRING, entry.id());
                            item.setItemMeta(meta);
                            var overflow = player.getInventory().addItem(item);
                            if (!overflow.isEmpty()) {
                                plugin.getLogger().warning("Mailbox capacity changed while delivering to "
                                        + player.getUniqueId() + "; item remains pending.");
                                continue;
                            }
                            player.saveData();
                            markMailClaimed(entry.id(), player.getUniqueId());
                            player.sendMessage("Delivered one item-mail parcel to your inventory.");
                        } catch (IllegalArgumentException exception) {
                            plugin.getLogger().severe("Could not decode mailbox item " + entry.id() + ": "
                                    + exception.getMessage());
                        }
                    }
                });
            } catch (SQLException exception) {
                plugin.getLogger().warning("Could not load mailbox for " + player.getUniqueId() + ": "
                        + exception.getMessage());
            }
        });
    }

    private boolean hasMailMarker(Player player, String mailId) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || !item.hasItemMeta()) {
                continue;
            }
            String marked = item.getItemMeta().getPersistentDataContainer().get(mailKey, PersistentDataType.STRING);
            if (mailId.equals(marked)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> getTaggedSellLines(Player player) {
        Set<String> ids = new java.util.HashSet<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || !item.hasItemMeta()) {
                continue;
            }
            String id = item.getItemMeta().getPersistentDataContainer()
                    .get(sellItemKey, PersistentDataType.STRING);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private void clearSellMarkers(Player player) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || !item.hasItemMeta()) {
                continue;
            }
            var container = item.getItemMeta().getPersistentDataContainer();
            String id = container.get(sellItemKey, PersistentDataType.STRING);
            if (id == null) {
                continue;
            }
            var meta = item.getItemMeta();
            meta.getPersistentDataContainer().remove(sellItemKey);
            item.setItemMeta(meta);
        }
        player.saveData();
    }

    private static boolean canFit(Player player, ItemStack item) {
        int remaining = item.getAmount();
        for (ItemStack current : player.getInventory().getStorageContents()) {
            if (current == null || current.getType().isAir()) {
                remaining -= item.getMaxStackSize();
            } else if (current.isSimilar(item)) {
                remaining -= Math.max(0, current.getMaxStackSize() - current.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    private void markMailClaimed(String mailId, UUID playerId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                database.markMailClaimed(mailId, playerId, System.currentTimeMillis());
            } catch (SQLException exception) {
                plugin.getLogger().warning("Could not acknowledge mailbox parcel " + mailId + ": "
                        + exception.getMessage());
            }
        });
    }

    private void checkAfk() {
        long thresholdMillis = Math.max(60, plugin.getConfig().getInt("afk.inactivity-seconds", 300)) * 1000L;
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (now - lastActivity.getOrDefault(player.getUniqueId(), now) >= thresholdMillis
                    && afk.add(player.getUniqueId())) {
                player.sendMessage("You are now AFK.");
            }
        }
    }

    private void flushActivity() {
        for (UUID playerId : pendingActivity.keySet()) {
            flushPlayer(playerId);
        }
    }

    private void flushPlayer(UUID playerId) {
        Activity activity = pendingActivity.remove(playerId);
        if (activity == null) {
            activity = new Activity();
        }
        long started = sessionStarted.getOrDefault(playerId, System.currentTimeMillis());
        long now = System.currentTimeMillis();
        long seconds = Math.max(0, (now - started) / 1000L);
        sessionStarted.put(playerId, now);
        Activity batch = activity;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                database.recordActivity(playerId, seconds, batch.placed, batch.broken, batch.deaths);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Could not save player activity for " + playerId + ": "
                        + exception.getMessage());
                pendingActivity.merge(playerId, batch, Activity::add);
            }
        });
    }

    private static java.util.Optional<Location> findSafeLocation(World world, int minRadius, int maxRadius) {
        World selectedWorld = world.getEnvironment() != World.Environment.NORMAL
                ? Bukkit.getWorlds().stream()
                    .filter(candidate -> candidate.getEnvironment() == World.Environment.NORMAL)
                    .findFirst().orElse(world)
                : world;
        var random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 64; attempt++) {
            double angle = random.nextDouble(0, Math.PI * 2);
            int radius = random.nextInt(Math.max(1, minRadius), Math.max(minRadius + 1, maxRadius + 1));
            int x = selectedWorld.getSpawnLocation().getBlockX() + (int) (Math.cos(angle) * radius);
            int z = selectedWorld.getSpawnLocation().getBlockZ() + (int) (Math.sin(angle) * radius);
            if (Math.abs(x - selectedWorld.getWorldBorder().getCenter().getBlockX()) > selectedWorld.getWorldBorder().getSize() / 2
                    || Math.abs(z - selectedWorld.getWorldBorder().getCenter().getBlockZ()) > selectedWorld.getWorldBorder().getSize() / 2) {
                continue;
            }
            Block ground = selectedWorld.getHighestBlockAt(x, z);
            Material groundType = ground.getType();
            if (!groundType.isSolid() || UNSAFE_GROUND.contains(groundType)) {
                continue;
            }
            Block feet = ground.getRelative(0, 1, 0);
            Block head = ground.getRelative(0, 2, 0);
            if (!feet.isPassable() || !head.isPassable()) {
                continue;
            }
            return java.util.Optional.of(new Location(selectedWorld, x + 0.5, ground.getY() + 1.0, z + 0.5));
        }
        Location spawn = selectedWorld.getSpawnLocation();
        Block ground = selectedWorld.getHighestBlockAt(spawn.getBlockX(), spawn.getBlockZ());
        if (ground.getType().isSolid() && !UNSAFE_GROUND.contains(ground.getType())
                && ground.getRelative(0, 1, 0).isPassable() && ground.getRelative(0, 2, 0).isPassable()) {
            return java.util.Optional.of(new Location(selectedWorld, spawn.getX(), ground.getY() + 1, spawn.getZ()));
        }
        return java.util.Optional.empty();
    }

    private static final class Activity {
        private long placed;
        private long broken;
        private long deaths;

        private static Activity add(Activity left, Activity right) {
            left.placed += right.placed;
            left.broken += right.broken;
            left.deaths += right.deaths;
            return left;
        }
    }
}