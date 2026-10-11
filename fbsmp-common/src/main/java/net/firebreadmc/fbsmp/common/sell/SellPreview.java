package net.firebreadmc.fbsmp.common.sell;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregate result of evaluating a whole inventory for a sell-all preview.
 * Pure; the GUI and the command layer both render this.
 */
public record SellPreview(long totalMinor, int soldTypes, long soldItems, List<Kept> kept) {

    public record Kept(String itemId, long quantity, SellEligibilityPolicy.Reason reason, String message) {}

    public record Line(String itemId, SellEligibilityPolicy.Decision decision) {}

    public static SellPreview of(List<Line> lines) {
        long total = 0;
        int types = 0;
        long items = 0;
        List<Kept> kept = new ArrayList<>();
        for (Line line : lines) {
            SellEligibilityPolicy.Decision decision = line.decision();
            if (decision.eligible()) {
                total = Math.addExact(total, decision.totalValueMinor());
                items = Math.addExact(items, decision.quantity());
                types++;
            } else if (line.itemId() != null) {
                kept.add(new Kept(line.itemId(), Math.max(0, decision.quantity()), decision.reason(),
                        decision.message()));
            }
        }
        return new SellPreview(total, types, items, List.copyOf(kept));
    }

    public boolean empty() {
        return soldTypes == 0;
    }
}
