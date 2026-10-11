package net.firebreadmc.fbsmp.common.shop;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The shop catalog: every item with exactly one category and a price decision,
 * grouped by category and paginated. Pure and deterministic (no server types), so
 * pagination and filtering are unit-testable.
 */
public final class ShopCatalog {

    /** Usable slots per GUI page (rows 0-4 of a 54-slot chest). */
    public static final int PAGE_SIZE = 45;

    public record Entry(String itemId, ItemCategory category, PriceTier tier,
                        Long buyMinor, Long sellMinor, boolean restricted, boolean requiresReview) {
        public boolean buyable() {
            return !restricted && buyMinor != null && buyMinor > 0;
        }
    }

    public record Page(List<Entry> entries, int pageIndex, int pageCount) {
        public boolean hasPrevious() {
            return pageIndex > 0;
        }

        public boolean hasNext() {
            return pageIndex < pageCount - 1;
        }
    }

    private final Map<ItemCategory, List<Entry>> byCategory;
    private final List<Entry> all;

    private ShopCatalog(Map<ItemCategory, List<Entry>> byCategory, List<Entry> all) {
        this.byCategory = byCategory;
        this.all = all;
    }

    public static ShopCatalog from(PriceRegistry registry) {
        Map<ItemCategory, List<Entry>> grouped = new EnumMap<>(ItemCategory.class);
        List<Entry> all = new ArrayList<>();
        for (PriceRegistry.Rule rule : registry.rules()) {
            Entry entry = new Entry(rule.itemId(), rule.category(), rule.tier(),
                    rule.buyMinor(), rule.sellMinor(), rule.restricted(), rule.requiresReview());
            all.add(entry);
            grouped.computeIfAbsent(rule.category(), key -> new ArrayList<>()).add(entry);
        }
        Map<ItemCategory, List<Entry>> frozen = new EnumMap<>(ItemCategory.class);
        for (Map.Entry<ItemCategory, List<Entry>> e : grouped.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return new ShopCatalog(frozen, List.copyOf(all));
    }

    public List<Entry> entries(ItemCategory category) {
        return byCategory.getOrDefault(category, List.of());
    }

    /** Categories that are safe to show to players (excludes {@link ItemCategory#RESTRICTED}). */
    public List<ItemCategory> playerCategories() {
        List<ItemCategory> result = new ArrayList<>();
        for (ItemCategory category : ItemCategory.values()) {
            if (category != ItemCategory.RESTRICTED && !entries(category).isEmpty()) {
                result.add(category);
            }
        }
        return result;
    }

    public List<ItemCategory> allCategories() {
        List<ItemCategory> result = new ArrayList<>();
        for (ItemCategory category : ItemCategory.values()) {
            if (!entries(category).isEmpty()) {
                result.add(category);
            }
        }
        return result;
    }

    public List<Entry> all() {
        return all;
    }

    public int size() {
        return all.size();
    }

    public Page page(ItemCategory category, int pageIndex) {
        List<Entry> entries = entries(category);
        return pageOf(entries, pageIndex);
    }

    public Page page(List<Entry> entries, int pageIndex) {
        return pageOf(entries, pageIndex);
    }

    private static Page pageOf(List<Entry> entries, int pageIndex) {
        int pageCount = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int clamped = Math.max(0, Math.min(pageIndex, pageCount - 1));
        int from = clamped * PAGE_SIZE;
        int to = Math.min(entries.size(), from + PAGE_SIZE);
        return new Page(List.copyOf(entries.subList(from, to)), clamped, pageCount);
    }
}
