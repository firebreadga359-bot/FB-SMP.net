package net.firebreadmc.fbsmp.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {
    @Test
    void parsesExactMinorUnits() {
        assertEquals(12345, Money.parseMinorUnits("123.45"));
        assertEquals(100, Money.parseMinorUnits("1"));
    }

    @Test
    void rejectsUnsafeAmounts() {
        assertThrows(IllegalArgumentException.class, () -> Money.parseMinorUnits("0"));
        assertThrows(IllegalArgumentException.class, () -> Money.parseMinorUnits("-1"));
        assertThrows(IllegalArgumentException.class, () -> Money.parseMinorUnits("1.001"));
        assertThrows(IllegalArgumentException.class, () -> Money.parseMinorUnits("1e6"));
    }
}