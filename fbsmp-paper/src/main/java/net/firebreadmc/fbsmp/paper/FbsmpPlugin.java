package net.firebreadmc.fbsmp.paper;

import net.firebreadmc.fbsmp.common.sell.SellEligibilityPolicy;
import net.firebreadmc.fbsmp.paper.admin.PrivateAdminPanel;
import net.firebreadmc.fbsmp.paper.commands.CommandRouter;
import net.firebreadmc.fbsmp.paper.data.FbsmpDatabase;
import net.firebreadmc.fbsmp.paper.listeners.PlayerListener;
import net.firebreadmc.fbsmp.paper.loan.LoanService;
import net.firebreadmc.fbsmp.paper.sell.SellService;
import net.firebreadmc.fbsmp.paper.listeners.ModerationListener;
import net.firebreadmc.fbsmp.paper.menus.ShopMenu;
import net.firebreadmc.fbsmp.paper.menus.SellMenu;
import net.firebreadmc.fbsmp.paper.menus.MortgageMenu;
import net.firebreadmc.fbsmp.paper.menus.WarpMenu;
import net.firebreadmc.fbsmp.paper.menus.SettingsMenu;
import net.firebreadmc.fbsmp.paper.menus.AuctionMenu;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

public final class FbsmpPlugin extends JavaPlugin {
    private FbsmpDatabase database;
    private PrivateAdminPanel adminPanel;
    private long startedAtMillis;

    @Override
    public void onEnable() {
        startedAtMillis = System.currentTimeMillis();
        saveDefaultConfig();
        saveResourceIfMissing("economy.yml");
        saveResourceIfMissing("sell_prices.yml");
        saveResourceIfMissing("shop.yml");
        saveResourceIfMissing("auction.yml");
        saveResourceIfMissing("calendar.yml");
        saveResourceIfMissing("teleport.yml");
        saveResourceIfMissing("homes.yml");
        saveResourceIfMissing("warps.yml");
        saveResourceIfMissing("loans.yml");
        saveResourceIfMissing("mortgages.yml");
        saveResourceIfMissing("orders.yml");
        saveResourceIfMissing("gifts.yml");
        saveResourceIfMissing("settings.yml");
        saveResourceIfMissing("moderation.yml");
        saveResourceIfMissing("messages.yml");

        try {
            Path file = getDataFolder().toPath().resolve(getConfig().getString("database.file", "fbsmp.sqlite"));
            database = new FbsmpDatabase(file, getClassLoader());
        } catch (SQLException | IOException exception) {
            getLogger().severe("Could not open the FB-SMP database: " + exception.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        PlayerListener playerListener = new PlayerListener(this, database);
        new ModerationListener(this, database);
        ShopMenu shopMenu = new ShopMenu(this, database, playerListener);
        SellService sellService = new SellService(shopMenu, new SellEligibilityPolicy(
                SellEligibilityPolicy.Config.defaults()));
        SellMenu sellMenu = new SellMenu(this, database, playerListener, sellService);
        MortgageMenu mortgageMenu = new MortgageMenu(this, database);
        WarpMenu warpMenu = new WarpMenu(this, database);
        SettingsMenu settingsMenu = new SettingsMenu(this, database, playerListener);
        AuctionMenu auctionMenu = new AuctionMenu(this, database, playerListener);
        LoanService loanService = null;
        try {
            long calendarStart = Long.parseLong(database.calendarStart());
            loanService = new LoanService(this, database, calendarStart);
        } catch (SQLException exception) {
            getLogger().severe("Could not initialise the loan service: " + exception.getMessage());
        }
        CommandRouter router = new CommandRouter(this, database, playerListener, sellMenu, shopMenu,
                mortgageMenu, warpMenu, settingsMenu, auctionMenu, loanService);
        for (String commandName : new String[]{
                "bal", "baltop", "pay", "sethome", "home", "homes", "delhome", "warp", "rtpq",
                "tpa", "tpahere", "tpaccept", "tpdeny", "msg", "reply", "ignore", "stats", "settings",
                "afk", "sell", "shop", "ah", "ausell", "loan", "mort", "mortvu", "gift", "orders",
                "ban", "kick", "timeout", "fbsmp"
        }) {
            Objects.requireNonNull(getCommand(commandName), "Missing command declaration: " + commandName)
                    .setExecutor(router);
        }

        Bukkit.getPluginManager().registerEvents(playerListener, this);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                int expiredOrders = database.expireOpenOrders(System.currentTimeMillis());
                int expiredAuctions = database.expireAuctions(System.currentTimeMillis());
                if (expiredOrders + expiredAuctions > 0) {
                    getLogger().info("Expired " + expiredOrders + " community order(s) and "
                            + expiredAuctions + " auction(s); refunds and items were moved to player mail.");
                }
            } catch (SQLException exception) {
                getLogger().warning("Could not process expired listings: " + exception.getMessage());
            }
        }, 20L * 60, 20L * 60 * 5);
        if (loanService != null) {
            LoanService scheduled = loanService;
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                try {
                    scheduled.tick();
                } catch (SQLException exception) {
                    getLogger().warning("Loan tick failed: " + exception.getMessage());
                }
            }, 20L * 60, 20L * 60);
        }
        if (getConfig().getBoolean("admin-panel.enabled", true)) {
            try {
                adminPanel = new PrivateAdminPanel(this, database);
                adminPanel.start();
            } catch (IOException | IllegalArgumentException exception) {
                getLogger().severe("The private admin panel could not start: " + exception.getMessage());
            }
        }
        getLogger().info("FB-SMP enabled. Database migrations are current; Paper backend remains the data authority.");
    }

    @Override
    public void onDisable() {
        if (adminPanel != null) {
            adminPanel.close();
        }
        if (database != null) {
            try {
                database.close();
            } catch (SQLException exception) {
                getLogger().severe("Could not close the FB-SMP database cleanly: " + exception.getMessage());
            }
        }
    }

    public FbsmpDatabase database() {
        return database;
    }

    public void backupDatabase() throws SQLException, IOException {
        Path backupDirectory = getDataFolder().toPath().resolve("backups");
        java.nio.file.Files.createDirectories(backupDirectory);
        String fileName = "fbsmp-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now()) + ".sqlite";
        Path destination = backupDirectory.resolve(fileName);
        database.backupTo(destination);
        int retention = Math.max(1, getConfig().getInt("backups.retention-count", 20));
        try (var files = java.nio.file.Files.list(backupDirectory)) {
            for (Path old : files.filter(path -> path.getFileName().toString().endsWith(".sqlite"))
                    .sorted(java.util.Comparator.reverseOrder()).skip(retention).toList()) {
                java.nio.file.Files.deleteIfExists(old);
            }
        }
    }

    public long uptimeSeconds() {
        return Math.max(0L, (System.currentTimeMillis() - startedAtMillis) / 1000L);
    }

    private void saveResourceIfMissing(String name) {
        if (!getDataFolder().toPath().resolve(name).toFile().exists()) {
            saveResource(name, false);
        }
    }
}