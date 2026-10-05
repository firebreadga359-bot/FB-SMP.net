package net.firebreadmc.fbsmp.paper.menus;

import net.firebreadmc.fbsmp.common.Money;
import net.firebreadmc.fbsmp.paper.FbsmpPlugin;
import net.firebreadmc.fbsmp.paper.data.FbsmpDatabase;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class MortgageMenu implements Listener {
    private final FbsmpPlugin plugin;
    private final FbsmpDatabase database;

    public MortgageMenu(FbsmpPlugin plugin, FbsmpDatabase database) {
        this.plugin = plugin;
        this.database = database;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        YamlConfiguration config = loadConfig();
        ConfigurationSection assets = config.getConfigurationSection("available-properties");
        List<Offer> offers = new ArrayList<>();
        if (assets != null) {
            for (String key : assets.getKeys(false)) {
                try {
                    long price = Money.parseMinorUnits(assets.getString(key));
                    if (price > 0) {
                        offers.add(new Offer(key, price));
                    }
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Ignoring invalid mortgage asset price for " + key + ".");
                }
            }
        }
        OffersHolder holder = new OffersHolder(player.getUniqueId(), UUID.randomUUID(),
                offers.stream().limit(45).toList());
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.GOLD + "Mortgage assets");
        holder.inventory = inventory;
        for (int i = 0; i < holder.offers.size(); i++) {
            Offer offer = holder.offers.get(i);
            inventory.setItem(i, button(Material.BARREL, ChatColor.YELLOW + offer.asset,
                    "Listed price: " + Money.format(offer.priceMinor),
                    "Click to review and adjust your down payment."));
        }
        if (offers.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, ChatColor.RED + "No mortgage assets configured",
                    "Add asset prices to mortgages.yml before offering mortgages."));
        }
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder rawHolder = event.getView().getTopInventory().getHolder();
        if (!(rawHolder instanceof OffersHolder) && !(rawHolder instanceof OfferHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        if (rawHolder instanceof OffersHolder holder) {
            if (!holder.owner.equals(player.getUniqueId()) || event.getRawSlot() >= holder.offers.size()) {
                return;
            }
            openOffer(player, holder.offers.get(event.getRawSlot()));
            return;
        }
        OfferHolder holder = (OfferHolder) rawHolder;
        if (!holder.owner.equals(player.getUniqueId())) {
            return;
        }
        switch (event.getRawSlot()) {
            case 11 -> {
                holder.downPaymentPercent = Math.max(5, holder.downPaymentPercent - 5);
                refreshOffer(holder);
            }
            case 15 -> {
                holder.downPaymentPercent = Math.min(90, holder.downPaymentPercent + 5);
                refreshOffer(holder);
            }
            case 18 -> open(player);
            case 22 -> createMortgage(player, holder);
            default -> {
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof OffersHolder || holder instanceof OfferHolder) {
            event.setCancelled(true);
        }
    }

    private void openOffer(Player player, Offer offer) {
        YamlConfiguration config = loadConfig();
        double downPaymentPercent = config.getDouble("down-payment-percent", 20);
        if (!Double.isFinite(downPaymentPercent)) {
            downPaymentPercent = 20;
        }
        OfferHolder holder = new OfferHolder(player.getUniqueId(), UUID.randomUUID(), offer,
                downPaymentPercent);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                ChatColor.GOLD + "Mortgage / " + offer.asset);
        holder.inventory = inventory;
        inventory.setItem(11, button(Material.REDSTONE_BLOCK, ChatColor.RED + "Decrease by 5%",
                "Lower the down payment percentage."));
        inventory.setItem(15, button(Material.LAPIS_BLOCK, ChatColor.BLUE + "Increase by 5%",
                "Raise the down payment percentage."));
        inventory.setItem(18, button(Material.ARROW, ChatColor.YELLOW + "Cancel",
                "Return to the asset list."));
        refreshOffer(holder);
        player.openInventory(inventory);
    }

    private void refreshOffer(OfferHolder holder) {
        long down = percentage(holder.offer.priceMinor, holder.downPaymentPercent);
        long principal = holder.offer.priceMinor - down;
        double interestPercent = loadConfig().getDouble("interest-rate-percent", 5);
        long interest = percentage(principal, interestPercent);
        int installments = Math.max(1, Math.min(120, loadConfig().getInt("installments", 12)));
        long payment = (principal + interest + installments - 1) / installments;
        holder.inventory.setItem(13, button(Material.BOOK, ChatColor.WHITE + holder.offer.asset,
                "Asset price: " + Money.format(holder.offer.priceMinor),
                "Down payment (" + holder.downPaymentPercent + "%): " + Money.format(down),
                "Financed: " + Money.format(principal),
                "Interest: " + Money.format(interest),
                "Approx. installment: " + Money.format(payment) + " × " + installments));
        holder.inventory.setItem(22, button(Material.EMERALD_BLOCK, ChatColor.GREEN + "Confirm mortgage",
                "Pay down payment: " + Money.format(down),
                "All amounts are validated and recorded by the server."));
    }

    private void createMortgage(Player player, OfferHolder holder) {
        YamlConfiguration config = loadConfig();
        long down = percentage(holder.offer.priceMinor, holder.downPaymentPercent);
        long principal = holder.offer.priceMinor - down;
        long interest = percentage(principal, config.getDouble("interest-rate-percent", 5));
        int installments = Math.max(1, Math.min(120, config.getInt("installments", 12)));
        long calendarDay = Math.max(1, plugin.getConfig().getInt("calendar.day-length-minutes", 30))
                * 60_000L;
        long interval;
        try {
            interval = Math.multiplyExact(Math.max(1, config.getLong("payment-interval-days", 30)), calendarDay);
        } catch (ArithmeticException exception) {
            player.sendMessage(ChatColor.RED + "Mortgage payment interval is outside the supported range.");
            return;
        }
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean created = false;
            try {
                created = database.createMortgage(playerId, holder.offer.asset, holder.offer.priceMinor,
                        down, interest, installments, interval, System.currentTimeMillis());
            } catch (SQLException exception) {
                plugin.getLogger().warning("Mortgage application failed for " + playerId + ": "
                        + exception.getMessage());
            }
            boolean success = created;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(playerId);
                if (online == null) {
                    return;
                }
                online.closeInventory();
                online.sendMessage(success
                        ? ChatColor.GREEN + "Mortgage recorded for " + holder.offer.asset + ". Down payment: "
                            + Money.format(down) + ". Use /mort pay for installments."
                        : ChatColor.RED + "Asset unavailable or down payment could not be taken.");
            });
        });
    }

    private YamlConfiguration loadConfig() {
        return YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "mortgages.yml"));
    }

    private static long percentage(long amount, double percent) {
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING).longValueExact();
    }

    private static ItemStack button(Material material, String title, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(title);
        meta.setLore(java.util.Arrays.stream(loreLines).map(line -> ChatColor.GRAY + line).toList());
        item.setItemMeta(meta);
        return item;
    }

    private record Offer(String asset, long priceMinor) {}

    private abstract static class MenuHolder implements InventoryHolder {
        final UUID owner;
        @SuppressWarnings("unused")
        final UUID sessionId;
        Inventory inventory;

        private MenuHolder(UUID owner, UUID sessionId) {
            this.owner = owner;
            this.sessionId = sessionId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class OffersHolder extends MenuHolder {
        private final List<Offer> offers;

        private OffersHolder(UUID owner, UUID sessionId, List<Offer> offers) {
            super(owner, sessionId);
            this.offers = List.copyOf(offers);
        }
    }

    private static final class OfferHolder extends MenuHolder {
        private final Offer offer;
        private double downPaymentPercent;

        private OfferHolder(UUID owner, UUID sessionId, Offer offer, double downPaymentPercent) {
            super(owner, sessionId);
            this.offer = offer;
            this.downPaymentPercent = Math.max(5, Math.min(90, downPaymentPercent));
        }
    }
}