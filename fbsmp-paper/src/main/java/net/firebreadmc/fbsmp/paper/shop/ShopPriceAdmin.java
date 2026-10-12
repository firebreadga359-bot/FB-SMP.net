package net.firebreadmc.fbsmp.paper.shop;

import net.firebreadmc.fbsmp.common.Money;
import net.firebreadmc.fbsmp.common.shop.ShopCatalog;
import net.firebreadmc.fbsmp.paper.menus.ShopMenu;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Admin-side editor for the shop and sell price files. Every change is written to
 * {@code shop.yml}/{@code sell_prices.yml}, re-validated through
 * {@link ShopPricing}, and pushed to the live {@link ShopMenu} so {@code /shop}
 * and {@code /sell} reflect it immediately. The previous file contents are kept
 * so the last edit can be undone.
 */
public final class ShopPriceAdmin {

    private final File dataFolder;
    private final ShopMenu shopMenu;
    private final File shopFile;
    private final File sellFile;

    private String lastShopBackup;
    private String lastSellBackup;

    public ShopPriceAdmin(File dataFolder, ShopMenu shopMenu) {
        this.dataFolder = dataFolder;
        this.shopMenu = shopMenu;
        this.shopFile = new File(dataFolder, "shop.yml");
        this.sellFile = new File(dataFolder, "sell_prices.yml");
    }

    public record PriceRow(String itemId, long buyMinor, long sellMinor, String category,
                           boolean buyable, boolean sellable, boolean disabled) {}

    public List<PriceRow> list(String search, int limit) {
        ShopCatalog catalog = shopMenu.catalog();
        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<PriceRow> rows = new ArrayList<>();
        for (ShopCatalog.Entry entry : catalog.all()) {
            if (!needle.isEmpty() && !entry.itemId().contains(needle)) {
                continue;
            }
            Long buy = shopMenu.buyPrice(entry.itemId());
            Long sell = shopMenu.sellPrice(entry.itemId());
            rows.add(new PriceRow(entry.itemId(), buy == null ? 0 : buy, sell == null ? 0 : sell,
                    entry.category().name(), entry.buyable(), entry.sellable(),
                    shopMenu.registry().disabledItems().contains(entry.itemId())));
            if (rows.size() >= Math.max(1, Math.min(500, limit))) {
                break;
            }
        }
        return rows;
    }

    public PriceRow price(String itemId) {
        String id = ShopPricing.normalizeKey(itemId);
        ShopCatalog.Entry entry = shopMenu.catalog().find(id);
        Long buy = shopMenu.buyPrice(id);
        Long sell = shopMenu.sellPrice(id);
        return new PriceRow(id, buy == null ? 0 : buy, sell == null ? 0 : sell,
                entry == null ? "miscellaneous" : entry.category().name(),
                entry != null && entry.buyable(), entry != null && entry.sellable(),
                shopMenu.registry().disabledItems().contains(id));
    }

    /** Sets an explicit buy price. Sell prices must remain strictly below buy. */
    public synchronized void setBuy(String itemId, long buyMinor) throws IOException {
        String id = ShopPricing.normalizeKey(itemId);
        if (buyMinor <= 0) {
            throw new IllegalArgumentException("Buy price must be positive.");
        }
        Long sell = shopMenu.sellPrice(id);
        if (sell != null && sell >= buyMinor) {
            throw new IllegalArgumentException("Buy price must be higher than the sell price ("
                    + Money.format(sell) + ").");
        }
        YamlConfiguration config = load(shopFile);
        lastShopBackup = config.saveToString();
        config.set("prices." + id.toUpperCase(Locale.ROOT), decimal(buyMinor));
        save(config, shopFile);
        shopMenu.reload();
    }

    public synchronized void setSell(String itemId, long sellMinor) throws IOException {
        String id = ShopPricing.normalizeKey(itemId);
        if (sellMinor <= 0) {
            throw new IllegalArgumentException("Sell price must be positive.");
        }
        Long buy = shopMenu.buyPrice(id);
        if (buy != null && sellMinor >= buy) {
            throw new IllegalArgumentException("Sell price must be lower than the buy price ("
                    + Money.format(buy) + ").");
        }
        YamlConfiguration config = load(sellFile);
        lastSellBackup = config.saveToString();
        config.set("prices." + id.toUpperCase(Locale.ROOT), decimal(sellMinor));
        save(config, sellFile);
        shopMenu.reload();
    }

    public synchronized void setDisabled(String itemId, boolean disabled) throws IOException {
        String id = ShopPricing.normalizeKey(itemId);
        YamlConfiguration config = load(shopFile);
        lastShopBackup = config.saveToString();
        List<String> list = new ArrayList<>(config.getStringList("disabled-items"));
        list.removeIf(entry -> ShopPricing.normalizeKey(entry).equals(id));
        if (disabled) {
            list.add(id.toUpperCase(Locale.ROOT));
        }
        config.set("disabled-items", list);
        save(config, shopFile);
        shopMenu.reload();
    }

    /** Undoes the most recent buy-file or sell-file change, if any. */
    public synchronized boolean undoLast() throws IOException {
        if (lastShopBackup != null) {
            restore(lastShopBackup, shopFile);
            lastShopBackup = null;
            shopMenu.reload();
            return true;
        }
        if (lastSellBackup != null) {
            restore(lastSellBackup, sellFile);
            lastSellBackup = null;
            shopMenu.reload();
            return true;
        }
        return false;
    }

    public String export() {
        StringBuilder builder = new StringBuilder();
        builder.append("# shop.yml\n").append(load(shopFile).saveToString());
        builder.append("# sell_prices.yml\n").append(load(sellFile).saveToString());
        return builder.toString();
    }

    public synchronized void importPrices(String yaml) throws IOException {
        String[] parts = yaml.split("(?m)^# sell_prices\\.yml$");
        if (parts.length == 2) {
            lastShopBackup = load(shopFile).saveToString();
            lastSellBackup = load(sellFile).saveToString();
            save(loadFromString(parts[0].replaceFirst("(?m)^# shop\\.yml$", "")), shopFile);
            save(loadFromString(parts[1]), sellFile);
            shopMenu.reload();
        } else {
            throw new IllegalArgumentException("Import must contain both shop.yml and sell_prices.yml sections.");
        }
    }

    private static YamlConfiguration load(File file) {
        return YamlConfiguration.loadConfiguration(file);
    }

    private static YamlConfiguration loadFromString(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalArgumentException("Invalid YAML: " + exception.getMessage());
        }
        return config;
    }

    private static void save(YamlConfiguration config, File file) throws IOException {
        ConfigurationSection section = config;
        if (section == null) {
            throw new IOException("Empty configuration.");
        }
        config.save(file);
    }

    private static void restore(String contents, File file) throws IOException {
        YamlConfiguration config = loadFromString(contents);
        config.save(file);
    }

    private static String decimal(long minor) {
        return BigDecimal.valueOf(minor, 2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
