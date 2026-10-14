package net.firebreadmc.fbsmp.paper.shop;

import net.firebreadmc.fbsmp.common.shop.ItemCategory;
import net.firebreadmc.fbsmp.common.shop.PriceRegistry;
import net.firebreadmc.fbsmp.common.shop.PriceValidator;
import net.firebreadmc.fbsmp.common.shop.ShopCatalog;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the shipped {@code shop.yml}/{@code sell_prices.yml}: the owner's catalogue
 * must price every listed item and stay free of arbitrage. Parses the YAML directly
 * so it needs no running server (the item registry would otherwise require Bukkit).
 */
class ShopPricingConfigTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(String name) throws Exception {
        File file = new File("src/main/resources", name);
        assertTrue(file.isFile(), "missing " + file.getAbsolutePath());
        try (FileInputStream in = new FileInputStream(file)) {
            return new Yaml().load(in);
        }
    }

    private PriceRegistry registry() throws Exception {
        Map<String, Object> shop = yaml("shop.yml");
        Map<String, Object> sell = yaml("sell_prices.yml");

        PriceRegistry.Builder builder = PriceRegistry.builder();
        Map<String, Object> buy = (Map<String, Object>) shop.get("prices");
        Map<String, Object> sellPrices = (Map<String, Object>) sell.get("prices");
        for (Map.Entry<String, Object> entry : buy.entrySet()) {
            String id = ShopPricing.normalizeKey(entry.getKey());
            builder.explicitBuy(id, minor(entry.getValue()));
            Object sellValue = sellPrices.get(entry.getKey());
            if (sellValue != null) {
                builder.explicitSell(id, minor(sellValue));
            }
        }

        // Category membership and display order come from the categories block, as
        // ShopPricing.readCategories does at runtime.
        List<String> ids = new ArrayList<>();
        int order = 0;
        Map<String, Object> categories = (Map<String, Object>) shop.get("categories");
        for (Map.Entry<String, Object> category : categories.entrySet()) {
            ItemCategory kind = categoryFor(category.getKey());
            for (Object raw : (List<Object>) ((Map<String, Object>) category.getValue()).get("items")) {
                String id = ShopPricing.normalizeKey(String.valueOf(raw));
                ids.add(id);
                builder.category(id, kind);
                builder.order(id, order++);
            }
        }
        return builder.build(ids);
    }

    /** Mirrors {@link ShopPricing#categoryFor}: matches the enum name or display name. */
    private static ItemCategory categoryFor(String name) {
        for (ItemCategory category : ItemCategory.values()) {
            if (category.name().equalsIgnoreCase(name)
                    || category.displayName().equalsIgnoreCase(name)) {
                return category;
            }
        }
        return null;
    }

    private static long minor(Object raw) {
        return Math.round(Double.parseDouble(String.valueOf(raw)) * 100);
    }

    @Test
    void everyConfiguredItemIsBuyableAndSellable() throws Exception {
        PriceRegistry registry = registry();
        assertEquals(120, registry.rules().size());
        for (PriceRegistry.Rule rule : registry.rules()) {
            assertTrue(rule.buyable(), "not buyable: " + rule.itemId() + " (" + rule.source() + ")");
            assertTrue(rule.sellable(), "not sellable: " + rule.itemId());
            assertTrue(rule.sellMinor() < rule.buyMinor(), "arbitrage: " + rule.itemId());
        }
    }

    @Test
    void configPassesThePriceValidator() throws Exception {
        PriceValidator.Report report = PriceValidator.validate(registry());
        // The only tolerated entry is the informational tier-median calibration note;
        // explicit owner prices override tiers, so it never affects gameplay.
        assertTrue(report.violations().stream()
                        .allMatch(v -> v.itemId().equals("<tiers>")),
                () -> report.violations().toString());
    }

    @Test
    void armourAndWeaponsCategoriesExist() throws Exception {
        ShopCatalog catalog = ShopCatalog.from(registry());
        assertFalse(catalog.entries(ItemCategory.ARMOR).isEmpty());
        assertFalse(catalog.entries(ItemCategory.WEAPONS).isEmpty());
        assertTrue(catalog.playerCategories().contains(ItemCategory.ARMOR));
        assertTrue(catalog.playerCategories().contains(ItemCategory.WEAPONS));
    }

    @Test
    void itemsKeepTheConfiguredDisplayOrder() throws Exception {
        List<String> building = ShopCatalog.from(registry()).entries(ItemCategory.BUILDING).stream()
                .map(ShopCatalog.Entry::itemId).toList();
        assertEquals("stone", building.get(0));
        assertEquals("cobbled_deepslate", building.get(1));
        // Deterministic across loads (the old Map.copyOf order was not).
        assertEquals(building, ShopCatalog.from(registry()).entries(ItemCategory.BUILDING).stream()
                .map(ShopCatalog.Entry::itemId).toList());
    }
}
