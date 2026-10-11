package net.firebreadmc.fbsmp.common.shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemClassifierTest {

    @Test
    void restrictedItemsAreDetected() {
        assertTrue(ItemClassifier.isRestricted("bedrock"));
        assertTrue(ItemClassifier.isRestricted("barrier"));
        assertTrue(ItemClassifier.isRestricted("command_block"));
        assertTrue(ItemClassifier.isRestricted("dragon_egg"));
        assertTrue(ItemClassifier.isRestricted("tnt"));
        assertTrue(ItemClassifier.isRestricted("zombie_spawn_egg"));
        assertTrue(ItemClassifier.isRestricted("player_head"));
        assertTrue(ItemClassifier.isRestricted("infested_stone"));
        assertTrue(ItemClassifier.isRestricted("air"));
        assertTrue(ItemClassifier.isRestricted("minecraft:cave_air"));
        assertFalse(ItemClassifier.isRestricted("stone"));
        assertFalse(ItemClassifier.isRestricted("diamond"));
    }

    @Test
    void restrictedClassificationCarriesAReasonAndNoPrices() {
        var classification = ItemClassifier.classify("bedrock");
        assertTrue(classification.restricted());
        assertEquals(ItemCategory.RESTRICTED, classification.category());
        assertFalse(classification.note().isBlank());
    }

    @Test
    void categoriesAreAssignedSensibly() {
        assertEquals(ItemCategory.MINING, ItemClassifier.classify("iron_ingot").category());
        assertEquals(ItemCategory.MINING, ItemClassifier.classify("oak_log").category());
        assertEquals(ItemCategory.BUILDING, ItemClassifier.classify("oak_planks").category());
        assertEquals(ItemCategory.BUILDING, ItemClassifier.classify("stone_bricks").category());
        assertEquals(ItemCategory.FARMING, ItemClassifier.classify("wheat_seeds").category());
        assertEquals(ItemCategory.FOOD, ItemClassifier.classify("bread").category());
        assertEquals(ItemCategory.UTILITY, ItemClassifier.classify("crafting_table").category());
        assertEquals(ItemCategory.DECORATION, ItemClassifier.classify("lantern").category());
        assertEquals(ItemCategory.DECORATION, ItemClassifier.classify("dandelion").category());
    }

    @Test
    void highValueItemsRequireReview() {
        assertTrue(ItemClassifier.classify("elytra").requiresReview());
        assertTrue(ItemClassifier.classify("nether_star").requiresReview());
        assertTrue(ItemClassifier.classify("netherite_ingot").requiresReview());
        assertFalse(ItemClassifier.classify("dirt").requiresReview());
    }

    @Test
    void namespacedIdsAreNormalised() {
        assertEquals(ItemCategory.MINING, ItemClassifier.classify("minecraft:iron_ingot").category());
        assertEquals("iron_ingot", ItemClassifier.normalize("Minecraft:Iron_Ingot"));
    }
}
