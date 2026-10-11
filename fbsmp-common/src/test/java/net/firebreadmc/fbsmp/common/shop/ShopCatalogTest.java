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
}
