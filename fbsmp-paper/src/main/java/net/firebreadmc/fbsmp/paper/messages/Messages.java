package net.firebreadmc.fbsmp.paper.messages;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Player-facing text loaded from {@code messages.yml}, so wording can change
 * without a rebuild. Undefined keys fall back to the caller's default, which
 * keeps every call site safe if the file is trimmed.
 *
 * <p>Values may contain {@code {placeholder}} tokens; the caller supplies the
 * values so admins can reorder or drop them.
 */
public final class Messages {

    private final File file;
    private final Map<String, String> values = new HashMap<>();
    private String prefix = "&8[&6FB-SMP&8] &r";

    public Messages(JavaPlugin plugin) {
        this(new File(plugin.getDataFolder(), "messages.yml"));
    }

    public Messages(File file) {
        this.file = file;
        reload();
    }

    public void reload() {
        values.clear();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        prefix = config.getString("prefix", prefix);
        for (String key : config.getKeys(true)) {
            if ("prefix".equals(key) || config.isConfigurationSection(key)) {
                continue;
            }
            String value = config.getString(key);
            if (value != null) {
                values.put(key, value);
            }
        }
    }

    /** Rendered, colored message for a key, or the fallback when the key is absent. */
    public String format(String key, String fallback, Map<String, String> placeholders) {
        String template = values.getOrDefault(key, fallback);
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                template = template.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return color(template);
    }

    public String format(String key, String fallback) {
        return format(key, fallback, Map.of());
    }

    /** The colored prefix on its own. */
    public String prefix() {
        return color(prefix);
    }

    private static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }
}
