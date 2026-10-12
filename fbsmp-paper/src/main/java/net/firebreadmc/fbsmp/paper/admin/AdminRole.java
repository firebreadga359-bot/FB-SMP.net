package net.firebreadmc.fbsmp.paper.admin;

import java.util.Set;

/**
 * Dashboard roles. Permissions are capability strings checked server-side for
 * every sensitive request; the legacy shared token authenticates as {@link #OWNER}.
 */
public enum AdminRole {
    OWNER(Set.of(
            "status.view", "logs.view", "economy.view", "economy.manage",
            "players.view", "players.manage", "moderation.manage",
            "shop.view", "shop.manage", "server.control", "console.send",
            "worlds.manage", "backups.manage", "plugins.manage", "announce.manage",
            "schedule.manage", "settings.manage", "admins.manage")),
    ADMINISTRATOR(Set.of(
            "status.view", "logs.view", "economy.view", "economy.manage",
            "players.view", "players.manage", "moderation.manage",
            "shop.view", "shop.manage", "server.control", "console.send",
            "worlds.manage", "backups.manage", "plugins.manage", "announce.manage",
            "schedule.manage", "settings.manage")),
    MODERATOR(Set.of(
            "status.view", "logs.view", "players.view", "moderation.manage",
            "announce.manage")),
    VIEWER(Set.of(
            "status.view", "logs.view", "players.view"));

    private final Set<String> permissions;

    AdminRole(Set<String> permissions) {
        this.permissions = permissions;
    }

    public boolean allows(String permission) {
        return permissions.contains(permission);
    }

    public Set<String> permissions() {
        return permissions;
    }

    public static AdminRole parse(String value, AdminRole fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }
}
