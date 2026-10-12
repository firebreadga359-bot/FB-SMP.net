package net.firebreadmc.fbsmp.common.shop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShopCatalogTest {

    private ShopCatalog catalogWith(int buildingItems) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < buildingItems; i++) {
            ids.add("stone_" + i + "_bricks");
        }
        ids.add("iron_ingot");
        ids.add("bedrock");
        return ShopCatalog.from(PriceRegistry.builder().build(ids));
    }

    @Test
    void paginationSplitsIntoCeilNPages() {
        for (int n : new int[]{0, 1, 45, 46, 90, 91, 200}) {
            ShopCatalog catalog = catalogWith(n);
            var entries = catalog.entries(ItemCategory.BUILDING);
            var page = catalog.page(ItemCategory.BUILDING, 0);
            int expectedPages = Math.max(1, (entries.size() + 44) / 45);
            assertEquals(expectedPages, page.pageCount(), "n=" + n);
            assertEquals(Math.min(45, entries.size()), page.entries().size(), "n=" + n);
        }
    }

    @Test
    void pageIndexIsClampedToTheValidRange() {
        ShopCatalog catalog = catalogWith(100);
        assertEquals(0, catalog.page(ItemCategory.BUILDING, -5).pageIndex());
        int lastPage = catalog.page(ItemCategory.BUILDING, 0).pageCount() - 1;
        assertEquals(lastPage, catalog.page(ItemCategory.BUILDING, 999).pageIndex());
        assertTrue(catalog.page(ItemCategory.BUILDING, lastPage).hasPrevious());
        assertFalse(catalog.page(ItemCategory.BUILDING, lastPage).hasNext());
    }

    @Test
    void restrictedCategoryIsHiddenFromPlayers() {
        ShopCatalog catalog = catalogWith(3);
        assertFalse(catalog.playerCategories().contains(ItemCategory.RESTRICTED));
        assertTrue(catalog.allCategories().contains(ItemCategory.RESTRICTED));
        assertFalse(catalog.entries(ItemCategory.RESTRICTED).isEmpty());
    }

    @Test
    void everyEntryHasExactlyOneCategory() {
        ShopCatalog catalog = catalogWith(3);
        for (ShopCatalog.Entry entry : catalog.all()) {
            assertNotNull(entry.category());
            long matches = catalog.allCategories().stream()
                    .filter(category -> catalog.entries(category).contains(entry)).count();
            assertEquals(1, matches, entry.itemId());
        }
    }

    @Test
    void findReturnsTheEntryOrNull() {
        ShopCatalog catalog = catalogWith(2);
        ShopCatalog.Entry found = catalog.find("iron_ingot");
        assertNotNull(found);
        assertEquals("iron_ingot", found.itemId());
        assertNull(catalog.find("definitely_not_an_item"));
        assertNull(catalog.find(null));
    }

    @Test
    void searchMatchesSubstringsCaseInsensitivelyAndCaps() {
        ShopCatalog catalog = catalogWith(10);
        var hits = catalog.search("STONE", 500);
        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().allMatch(entry -> entry.itemId().contains("stone")));
        assertEquals(3, catalog.search("stone", 3).size());
        assertTrue(catalog.search("", 10).isEmpty());
        assertTrue(catalog.search(null, 10).isEmpty());
    }

    @Test
    void buyableAndSellableExcludeRestrictedEntries() {
        ShopCatalog catalog = catalogWith(3);
        assertTrue(catalog.buyable().stream().allMatch(ShopCatalog.Entry::buyable));
        assertTrue(catalog.sellable().stream().allMatch(ShopCatalog.Entry::sellable));
        // bedrock lands in the restricted category and must not be buyable or sellable.
        assertTrue(catalog.find("bedrock") == null || !catalog.find("bedrock").buyable());
        assertTrue(catalog.buyable().stream().noneMatch(entry -> entry.category() == ItemCategory.RESTRICTED));
    }
}
