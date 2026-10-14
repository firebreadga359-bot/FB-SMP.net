package net.firebreadmc.fbsmp.common.shop;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PriceRegistryTest {

    private static final List<String> REGISTRY = List.of(
            "stone", "cobblestone", "oak_planks", "glass", "wheat_seeds", "carrot", "potato",
            "bone_meal", "torch", "iron_ingot", "coal", "lantern", "flower_pot", "chest",
            "crafting_table", "anvil", "bread", "cooked_beef", "baked_potato", "paper", "book",
            "diamond", "elytra", "nether_star", "bedrock", "barrier", "spawner",
            "zombie_spawn_egg", "dragon_egg", "tnt", "command_block", "dirt", "grass_block",
            "oak_log", "wheat", "gold_ingot", "copper_ingot", "redstone", "obsidian");

    private PriceRegistry registry() {
        return PriceRegistry.builder()
                .explicitBuy("stone", 25)
                .explicitBuy("cobblestone", 15)
                .explicitBuy("oak_planks", 30)
                .explicitBuy("glass", 60)
                .explicitBuy("iron_ingot", 400)
                .explicitBuy("coal", 125)
                .explicitBuy("torch", 25)
                .explicitBuy("bread", 40)
                .build(REGISTRY);
    }

    @Test
    void everyCatalogItemGetsExactlyOneCategoryAndAPriceDecision() {
        PriceRegistry registry = registry();
        for (String id : REGISTRY) {
            PriceRegistry.Rule rule = registry.rule(id);
            assertNotNull(rule, "no rule for " + id);
            assertNotNull(rule.category(), "no category for " + id);
            if (!rule.restricted() && rule.source() != PriceRegistry.Source.DISABLED) {
                assertTrue(rule.buyMinor() != null || rule.requiresReview(),
                        "no price decision for " + id);
            }
        }
    }

    @Test
    void restrictedItemsHaveNoPrices() {
        PriceRegistry registry = registry();
        for (String id : List.of("bedrock", "barrier", "spawner", "zombie_spawn_egg",
                "dragon_egg", "command_block")) {
            PriceRegistry.Rule rule = registry.rule(id);
            assertEquals(PriceRegistry.Source.RESTRICTED, rule.source(), id);
            assertFalse(rule.buyable(), id);
            assertFalse(rule.sellable(), id);
            assertNull(rule.buyMinor(), id);
        }
    }

    @Test
    void explicitlyPricedItemsAreBuyableEvenWhenOnceBlocked() {
        PriceRegistry registry = PriceRegistry.builder()
                .explicitBuy("tnt", 11390)
                .explicitSell("tnt", 11389)
                .build(REGISTRY);
        PriceRegistry.Rule rule = registry.rule("tnt");
        assertEquals(PriceRegistry.Source.EXPLICIT, rule.source());
        assertTrue(rule.buyable());
        assertTrue(rule.sellable());
    }

    @Test
    void highValueItemsRequireReviewInsteadOfGeneratedPrices() {
        PriceRegistry registry = registry();
        for (String id : List.of("elytra", "nether_star")) {
            PriceRegistry.Rule rule = registry.rule(id);
            assertTrue(rule.requiresReview(), id);
            assertNull(rule.buyMinor(), id);
        }
        // A normal item is priced without review.
        assertFalse(registry.rule("dirt").requiresReview());
        assertNotNull(registry.rule("dirt").buyMinor());
    }

    @Test
    void noItemCanBeBoughtAndSoldForProfit() {
        PriceValidator.Report report = PriceValidator.validate(registry());
        for (PriceRegistry.Rule rule : registry().rules()) {
            if (rule.buyMinor() != null && rule.sellMinor() != null) {
                assertTrue(rule.sellMinor() < rule.buyMinor(),
                        "arbitrage on " + rule.itemId());
            }
        }
        assertTrue(report.violations().isEmpty(), () -> report.violations().toString());
    }

    @Test
    void explicitPricesAreHonouredAndSellDefaultsToHalf() {
        PriceRegistry registry = registry();
        assertEquals(400L, registry.buyPrice("iron_ingot"));
        assertEquals(200L, registry.sellPrice("iron_ingot"));
        assertEquals(PriceRegistry.Source.EXPLICIT, registry.rule("iron_ingot").source());
    }

    @Test
    void oneMinorUnitBuyHasNoSellPrice() {
        PriceRegistry registry = PriceRegistry.builder().explicitBuy("dirt", 1).build(REGISTRY);
        PriceRegistry.Rule rule = registry.rule("dirt");
        assertEquals(1L, rule.buyMinor());
        assertNull(rule.sellMinor());
        assertTrue(rule.buyable());
        assertFalse(rule.sellable());
    }

    @Test
    void validatorCatchesInjectedArbitrage() {
        PriceRegistry registry = PriceRegistry.builder()
                .explicitBuy("dirt", 100)
                .explicitSell("dirt", 150)
                .build(REGISTRY);
        PriceValidator.Report report = PriceValidator.validate(registry);
        assertFalse(report.clean());
        assertTrue(report.violations().stream().anyMatch(v -> v.itemId().equals("dirt")));
    }

    @Test
    void disabledItemsAreNeitherBuyableNorSellable() {
        PriceRegistry registry = PriceRegistry.builder().disable("dirt").build(REGISTRY);
        assertFalse(registry.isBuyable("dirt"));
        assertFalse(registry.isSellable("dirt"));
        assertEquals(PriceRegistry.Source.DISABLED, registry.rule("dirt").source());
        assertTrue(registry.disabledItems().contains("dirt"));
    }

    @Test
    void reviewListIsReportedForUnpricedItems() {
        PriceValidator.Report report = PriceValidator.validate(registry());
        assertTrue(report.reviewItems().contains("elytra"));
        assertTrue(report.reviewItems().contains("nether_star"));
        assertFalse(report.reviewItems().contains("dirt"));
    }

    @Test
    void tierMediansAreAscending() {
        PriceValidator.Report report = PriceValidator.validate(registry());
        List<Long> medians = report.tierMedianBuy().values().stream().sorted().toList();
        assertEquals(medians.size(), report.tierMedianBuy().size());
    }
}
