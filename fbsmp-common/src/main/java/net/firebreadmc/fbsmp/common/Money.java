package net.firebreadmc.fbsmp.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public final class Money {
    public static final String SYMBOL = "₣฿";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Money() {}

    public static long parseMinorUnits(String input) {
        if (input == null || !input.matches("(?:0|[1-9]\\d*)(?:\\.\\d{1,2})?")) {
            throw new IllegalArgumentException("Enter a positive amount with up to two decimal places.");
        }
        try {
            long amount = new BigDecimal(input).setScale(2, RoundingMode.UNNECESSARY)
                    .multiply(HUNDRED).longValueExact();
            if (amount <= 0) {
                throw new IllegalArgumentException("Amount must be greater than zero.");
            }
            return amount;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Amount is outside the supported range.");
        }
    }

    public static String format(long minorUnits) {
        return SYMBOL + String.format(Locale.ROOT, "%,.2f", minorUnits / 100.0d);
    }

    public static String formatPlain(long minorUnits) {
        return String.format(Locale.ROOT, "%,.2f", minorUnits / 100.0d);
    }
}