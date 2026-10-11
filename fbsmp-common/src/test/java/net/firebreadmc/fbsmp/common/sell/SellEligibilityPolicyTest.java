package net.firebreadmc.fbsmp.common.sell;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SellEligibilityPolicyTest {

    private final SellEligibilityPolicy policy = new SellEligibilityPolicy(SellEligibilityPolicy.Config.defaults());

    private static SellStackView plain(String id, int amount) {
        return new SellStackView(id, amount, 64, false, false, false, false, false, false, 0, 0);
    }

    @Test
    void plainPricedItemIsEligible() {
        var decision = policy.evaluate(plain("cobblestone", 64), 15L, 7L);
        assertTrue(decision.eligible());
        assertEquals(7, decision.unitValueMinor());
        assertEquals(448, decision.totalValueMinor());
    }

    @Test
    void renamedLoreEnchantedAndCustomModelAreRefused() {
        assertEquals(SellEligibilityPolicy.Reason.CUSTOM_NAME,
                policy.evaluate(new SellStackView("iron_pickaxe", 1, 1, true, false, false, false, false, false, 250, 0), null, 100L).reason());
        assertEquals(SellEligibilityPolicy.Reason.LORE,
                policy.evaluate(new SellStackView("iron_pickaxe", 1, 1, false, true, false, false, false, false, 250, 0), null, 100L).reason());
        assertEquals(SellEligibilityPolicy.Reason.ENCHANTED,
                policy.evaluate(new SellStackView("iron_pickaxe", 1, 1, false, false, true, false, false, false, 250, 0), null, 100L).reason());
        assertEquals(SellEligibilityPolicy.Reason.CUSTOM_MODEL,
                policy.evaluate(new SellStackView("iron_pickaxe", 1, 1, false, false, false, true, false, false, 250, 0), null, 100L).reason());
    }

    @Test
    void containerWithContentsIsRefusedButEmptyIsAllowed() {
        assertEquals(SellEligibilityPolicy.Reason.CONTAINER_CONTENTS,
                policy.evaluate(new SellStackView("shulker_box", 1, 1, false, false, false, false, true, false, 0, 0), null, 500L).reason());
        assertTrue(policy.evaluate(new SellStackView("shulker_box", 1, 1, false, false, false, false, false, false, 0, 0), null, 500L).eligible());
    }

    @Test
    void guiMarkerIsNeverSold() {
        assertEquals(SellEligibilityPolicy.Reason.GUI_MARKER,
                policy.evaluate(new SellStackView("paper", 1, 64, false, false, false, false, false, true, 0, 0), null, 50L).reason());
    }

    @Test
    void unpricedItemIsPreservedWithAnExplanation() {
        var decision = policy.evaluate(plain("diamond", 1), 1000L, null);
        assertFalse(decision.eligible());
        assertEquals(SellEligibilityPolicy.Reason.UNPRICED, decision.reason());
        assertFalse(decision.message().isBlank());
    }

    @Test
    void damagedToolIsScaledByDurabilityAndRefusedBelowTheFloor() {
        var half = policy.evaluate(new SellStackView("iron_pickaxe", 1, 1, false, false, false, false, false, false, 250, 125), null, 100L);
        assertTrue(half.eligible());
        assertEquals(50, half.unitValueMinor());

        var broken = policy.evaluate(new SellStackView("iron_pickaxe", 1, 1, false, false, false, false, false, false, 250, 240), null, 100L);
        assertFalse(broken.eligible());
        assertEquals(SellEligibilityPolicy.Reason.LOW_DURABILITY, broken.reason());
    }

    @Test
    void previewAggregatesSoldAndKept() {
        SellPreview preview = SellPreview.of(List.of(
                new SellPreview.Line("cobblestone", policy.evaluate(plain("cobblestone", 64), 15L, 7L)),
                new SellPreview.Line("diamond", policy.evaluate(plain("diamond", 3), 1000L, null)),
                new SellPreview.Line("bread", policy.evaluate(plain("bread", 5), 40L, 20L))));
        assertEquals(2, preview.soldTypes());
        assertEquals(69, preview.soldItems());
        assertEquals(448 + 100, preview.totalMinor());
        assertEquals(1, preview.kept().size());
        assertEquals("diamond", preview.kept().get(0).itemId());
    }
}
