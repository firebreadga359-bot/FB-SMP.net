package net.firebreadmc.fbsmp.paper.listeners;

import net.firebreadmc.fbsmp.paper.FbsmpPlugin;
import net.firebreadmc.fbsmp.paper.data.FbsmpDatabase;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.sql.SQLException;
import java.util.UUID;

public final class ModerationListener implements Listener {
    private final FbsmpPlugin plugin;
    private final FbsmpDatabase database;

    public ModerationListener(FbsmpPlugin plugin, FbsmpDatabase database) {
        this.plugin = plugin;
        this.database = database;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        try {
            var ban = database.activeModeration(event.getUniqueId(), "BAN", System.currentTimeMillis());
            if (ban.isPresent()) {
                FbsmpDatabase.ModerationEntry record = ban.get();
                String expiry = record.expiresAt() == null ? "Permanent" : "Until " + record.expiresAt();
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                        "You are banned from FB-SMP.\nReason: " + record.reason() + "\n" + expiry);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not check moderation records for " + event.getUniqueId()
                    + ": " + exception.getMessage());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "FB-SMP could not verify your login status. Please try again shortly.");
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        try {
            var timeout = database.activeModeration(playerId, "TIMEOUT", System.currentTimeMillis());
            if (timeout.isPresent()) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("You are timed out and cannot send public messages. Reason: "
                        + timeout.get().reason());
            }
        } catch (SQLException exception) {
            event.setCancelled(true);
            plugin.getLogger().warning("Could not verify chat timeout for " + playerId + ": "
                    + exception.getMessage());
            event.getPlayer().sendMessage("Chat is temporarily unavailable while your moderation status is checked.");
        }
    }
}