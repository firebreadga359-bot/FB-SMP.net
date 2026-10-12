package net.firebreadmc.fbsmp.common.shop;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Rarity/price tier used to calibrate generated prices and to sanity-check the
 * buy/sell spread. Tier medians must increase with tier order.
 */
public enum PriceTier {
    COMMON(0.01, 5.00),
    UNCOMMON(5.00, 25.00),
    RARE(25.00, 150.00),
    EPIC(150.00, 1000.00),
    LEGENDARY(1000.00, 1_000_000.00);

    private final BigDecimal defaultBuyMin;
    private final BigDecimal defaultBuyMax;

    PriceTier(double buyMin, double buyMax) {
        this.defaultBuyMin = BigDecimal.valueOf(buyMin);
        this.defaultBuyMax = BigDecimal.valueOf(buyMax);
    }

    public BigDecimal defaultBuyMin() {
        return defaultBuyMin;
    }

    public BigDecimal defaultBuyMax() {
        return defaultBuyMax;
    }

    public static PriceTier parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Approximate mid-point buy price, rounded to whole minor units. */
    public long midpointMinor() {
        return defaultBuyMin.add(defaultBuyMax)
                .divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact();
    }
}
