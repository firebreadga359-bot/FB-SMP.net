package net.firebreadmc.fbsmp.paper.shop;

import net.firebreadmc.fbsmp.common.shop.ItemCategory;
import net.firebreadmc.fbsmp.common.shop.PriceRegistry;
import net.firebreadmc.fbsmp.common.shop.PriceTier;
import net.firebreadmc.fbsmp.common.shop.ShopCatalog;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Loads {@code shop.yml}/{@code sell_prices.yml} into a validated {@link PriceRegistry}
 * and builds the {@link ShopCatalog}.
 *
 * <p>Only real, obtainable materials from the server registry are considered; air and
 * legacy materials are skipped. Explicit prices always win over generated ones, so the
 * existing curated prices keep working unchanged.
 */
public final class ShopPricing {

    private ShopPricing() {}

    public record Loaded(PriceRegistry registry, ShopCatalog catalog) {}

    public static Loaded load(File dataFolder, Logger logger) {
        YamlConfiguration shop = YamlConfiguration.loadConfiguration(new File(dataFolder, "shop.yml"));
        YamlConfiguration sell = YamlConfiguration.loadConfiguration(new File(dataFolder, "sell_prices.yml"));

        PriceRegistry.Builder builder = PriceRegistry.builder();
        builder.sellRatio(decimal(shop.getString("sell-ratio"), PriceRegistry.DEFAULT_SELL_RATIO));

        readPrices(shop.getConfigurationSection("prices"), logger, builder, true);
        readPrices(sell.getConfigurationSection("prices"), logger, builder, false);
        readCategories(shop.getConfigurationSection("categories"), builder);
        for (String disabled : shop.getStringList("disabled-items")) {
            builder.disable(disabled);
        }

        List<String> registryIds = registryIds();
        PriceRegistry registry = builder.build(registryIds);
        return new Loaded(registry, ShopCatalog.from(registry));
    }

    private static void readPrices(ConfigurationSection section, Logger logger,
                                   PriceRegistry.Builder builder, boolean buy) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            String id = normalizeKey(key);
            long minor = parseMinor(section.get(key));
            if (minor <= 0) {
                logger.warning("Ignoring invalid price for " + key + " in shop/sell config.");
                continue;
            }
            if (buy) {
                builder.explicitBuy(id, minor);
            } else {
                builder.explicitSell(id, minor);
            }
        }
    }

    private static void readCategories(ConfigurationSection section, PriceRegistry.Builder builder) {
        if (section == null) {
            return;
        }
        int order = 0;
        for (String categoryName : section.getKeys(false)) {
            ItemCategory category = categoryFor(categoryName);
            if (category == null) {
                continue;
            }
            for (String item : section.getStringList(categoryName + ".items")) {
                String id = normalizeKey(item);
                builder.category(id, category);
                builder.order(id, order++);
            }
        }
    }

    private static ItemCategory categoryFor(String name) {
        for (ItemCategory category : ItemCategory.values()) {
            if (category.name().equalsIgnoreCase(name)
                    || category.displayName().equalsIgnoreCase(name)) {
                return category;
            }
        }
        return null;
    }

    private static long parseMinor(Object raw) {
        if (raw == null) {
            return -1;
        }
        try {
            return new BigDecimal(String.valueOf(raw).trim())
                    .setScale(2)
                    .movePointRight(2)
                    .longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            return -1;
        }
    }

    private static BigDecimal decimal(String raw, BigDecimal fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public static String normalizeKey(String key) {
        String id = key.trim().toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /** Every non-air material the server registry exposes, by lower-case name. */
    public static List<String> registryIds() {
        List<String> ids = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.isAir() && material.isItem() && !material.name().startsWith("LEGACY_")) {
                ids.add(material.name().toLowerCase(Locale.ROOT));
            }
        }
        return ids;
    }

    public static PriceTier tierOf(String itemId) {
        return net.firebreadmc.fbsmp.common.shop.ItemClassifier.classify(itemId).tier();
    }
}
