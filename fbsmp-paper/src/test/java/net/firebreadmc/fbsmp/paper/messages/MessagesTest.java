package net.firebreadmc.fbsmp.paper.messages;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link Messages} against a real {@code messages.yml} on disk: key
 * lookup, {@code {placeholder}} substitution, colour translation, fallback for
 * unknown keys and reload.
 */
class MessagesTest {

    private static Messages load(Path dataFolder) throws Exception {
        return new Messages(dataFolder.resolve("messages.yml").toFile());
    }

    private static void write(Path dataFolder, String contents) throws Exception {
        Files.writeString(dataFolder.resolve("messages.yml"), contents);
    }

    @Test
    void substitutesPlaceholdersAndTranslatesColour(@TempDir Path dataFolder) throws Exception {
        write(dataFolder, """
                prefix: "&8[&6FB-SMP&8] &r"
                shop:
                  purchased: "&aPurchased {count}x {item} for &6{amount}&a."
                """);
        Messages messages = load(dataFolder);

        String rendered = messages.format("shop.purchased", "unused",
                Map.of("count", "3", "item", "DIAMOND", "amount", "1.50"));

        assertEquals(ChatColor.translateAlternateColorCodes('&',
                "&aPurchased 3x DIAMOND for &61.50&a."), rendered);
    }

    @Test
    void missingKeyUsesFallback(@TempDir Path dataFolder) throws Exception {
        write(dataFolder, "prefix: \"&8[&6FB-SMP&8] &r\"\n");
        Messages messages = load(dataFolder);

        assertEquals(ChatColor.translateAlternateColorCodes('&', "&cFallback text."),
                messages.format("loan.unknown", "&cFallback text."));
    }

    @Test
    void reloadPicksUpEdits(@TempDir Path dataFolder) throws Exception {
        write(dataFolder, "loan:\n  unavailable: \"&cOriginal.\"\n");
        Messages messages = load(dataFolder);
        assertEquals(ChatColor.translateAlternateColorCodes('&', "&cOriginal."),
                messages.format("loan.unavailable", "fallback"));

        write(dataFolder, "loan:\n  unavailable: \"&cEdited.\"\n");
        messages.reload();

        assertEquals(ChatColor.translateAlternateColorCodes('&', "&cEdited."),
                messages.format("loan.unavailable", "fallback"));
    }

    @Test
    void shippedResourceContainsEveryWiredKey() throws Exception {
        // Guards against wiring a key in code that never lands in messages.yml.
        var resource = MessagesTest.class.getClassLoader().getResourceAsStream("messages.yml");
        assertTrue(resource != null, "messages.yml must ship in the plugin resources");
        String yaml;
        try (var in = resource) {
            yaml = new String(in.readAllBytes());
        }
        for (String key : new String[]{
                "prefix", "no-permission", "player-only",
                "shop.usage", "shop.not-sold", "shop.quantity-invalid", "shop.quantity-range",
                "shop.amount-range", "shop.insufficient-funds", "shop.purchased",
                "loan.unavailable", "loan.usage", "loan.take.usage", "loan.limit-exceeded",
                "loan.timed-out", "loan.issue-failed", "loan.issued", "loan.repay.insufficient",
                "loan.repay.none", "loan.repay.posted", "loan.info.none", "loan.info.header",
                "loan.info.timeout", "loan.info.footer"
        }) {
            String leaf = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
            assertTrue(yaml.contains(leaf + ":"), "messages.yml is missing key " + key);
        }
    }
}
