package net.firebreadmc.fbsmp.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimeoutTierSelectorTest {

    private static final List<TimeoutTierSelector.Tier> TIERS = List.of(
            new TimeoutTierSelector.Tier(1, 500_000L, 1_800, "small"),
            new TimeoutTierSelector.Tier(500_001, 2_500_000L, 7_200, "medium"),
            new TimeoutTierSelector.Tier(2_500_001, null, 21_600, "large"));

    @Test
    void selectsAtExactBoundaries() {
        var selector = new TimeoutTierSelector(TIERS, 86_400);
        assertEquals("small", selector.select(1).reason());
        assertEquals("small", selector.select(500_000).reason());
        assertEquals("medium", selector.select(500_001).reason());
        assertEquals("medium", selector.select(2_500_000).reason());
        assertEquals("large", selector.select(2_500_001).reason());
        assertEquals("large", selector.select(Long.MAX_VALUE).reason());
    }

    @Test
    void clampsDurationToTheHardMaximum() {
        var selector = new TimeoutTierSelector(TIERS, 3_600);
        assertEquals(1_800, selector.durationSeconds(selector.select(100)));
        assertEquals(3_600, selector.durationSeconds(selector.select(2_500_001)));
    }

    @Test
    void rejectsGapsAndOpenEndedNonFinalTiers() {
        assertThrows(IllegalArgumentException.class, () -> new TimeoutTierSelector(List.of(
                new TimeoutTierSelector.Tier(1, 100L, 60, "a"),
                new TimeoutTierSelector.Tier(200, null, 60, "b")), 1000));
        assertThrows(IllegalArgumentException.class, () -> new TimeoutTierSelector(List.of(
                new TimeoutTierSelector.Tier(1, null, 60, "a"),
                new TimeoutTierSelector.Tier(2, null, 60, "b")), 1000));
        assertThrows(IllegalArgumentException.class, () -> new TimeoutTierSelector(List.of(), 1000));
    }
}
