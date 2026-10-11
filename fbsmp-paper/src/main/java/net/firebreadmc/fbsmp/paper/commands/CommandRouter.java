package net.firebreadmc.fbsmp.paper.commands;

import net.firebreadmc.fbsmp.common.FbCalendar;
import net.firebreadmc.fbsmp.common.Money;
import net.firebreadmc.fbsmp.paper.FbsmpPlugin;
import net.firebreadmc.fbsmp.paper.data.FbsmpDatabase;
import net.firebreadmc.fbsmp.paper.listeners.PlayerListener;
import net.firebreadmc.fbsmp.paper.loan.LoanService;
import net.firebreadmc.fbsmp.paper.menus.SellMenu;
import net.firebreadmc.fbsmp.paper.menus.ShopMenu;
import net.firebreadmc.fbsmp.paper.menus.MortgageMenu;
import net.firebreadmc.fbsmp.paper.menus.WarpMenu;
import net.firebreadmc.fbsmp.paper.menus.SettingsMenu;
import net.firebreadmc.fbsmp.paper.menus.AuctionMenu;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

public final class CommandRouter implements CommandExecutor {
    private final FbsmpPlugin plugin;
    private final FbsmpDatabase database;
    private final Map<UUID, TeleportRequest> requestsByRecipient = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTeleportAt = new ConcurrentHashMap<>();
    private final Map<UUID, PendingGift> pendingGifts = new ConcurrentHashMap<>();
    private final PlayerListener listener;
    private final SellMenu sellMenu;
    private final ShopMenu shopMenu;
    private final MortgageMenu mortgageMenu;
    private final WarpMenu warpMenu;
    private final SettingsMenu settingsMenu;
    private final AuctionMenu auctionMenu;
    private final LoanService loanService;

    public CommandRouter(FbsmpPlugin plugin, FbsmpDatabase database, PlayerListener listener,
                         SellMenu sellMenu, ShopMenu shopMenu, MortgageMenu mortgageMenu,
                         WarpMenu warpMenu, SettingsMenu settingsMenu, AuctionMenu auctionMenu,
                         LoanService loanService) {
        this.plugin = plugin;
        this.database = database;
        this.listener = listener;
        this.sellMenu = sellMenu;
        this.shopMenu = shopMenu;
        this.mortgageMenu = mortgageMenu;
        this.warpMenu = warpMenu;
        this.settingsMenu = settingsMenu;
        this.auctionMenu = auctionMenu;
        this.loanService = loanService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "bal", "balance" -> balance(sender);
            case "baltop" -> baltop(sender);
            case "pay" -> pay(sender, args);
            case "sethome" -> setHome(sender, args);
            case "home" -> home(sender, args);
            case "homes" -> homes(sender);
            case "delhome" -> deleteHome(sender, args);
            case "warp" -> warp(sender, args);
            case "rtpq" -> randomTeleport(sender);
            case "tpa" -> teleportRequest(sender, args, false);
            case "tpahere" -> teleportRequest(sender, args, true);
            case "tpaccept" -> acceptTeleport(sender);
            case "tpdeny" -> denyTeleport(sender);
            case "msg", "tell", "w" -> privateMessage(sender, args);
            case "reply", "r" -> reply(sender, args);
            case "ignore" -> ignore(sender, args);
            case "stats" -> stats(sender);
            case "settings" -> settings(sender, args);
            case "afk" -> afk(sender);
            case "shop" -> shop(sender, args);
            case "sell" -> sell(sender);
            case "ah", "aushop" -> auctionHouse(sender, args);
            case "ausell" -> auctionSell(sender, args);
            case "loan" -> loan(sender, args);
            case "mort" -> mortgage(sender, args);
            case "mortvu" -> mortgageAccount(sender);
            case "gift" -> gift(sender, args);
            case "orders" -> orders(sender, args);
            case "ban" -> moderate(sender, args, "BAN");
            case "kick" -> moderate(sender, args, "KICK");
            case "timeout" -> moderate(sender, args, "TIMEOUT");
            case "fbsmp" -> admin(sender, args);
            default -> sender.sendMessage(color("&cUnknown FB-SMP command."));
        }
        return true;
    }

    private void balance(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        query(player, () -> database.balance(player.getUniqueId()), (target, balance) ->
                target.sendMessage(color("&6Your balance: &f" + Money.format(balance))));
    }

    private void baltop(CommandSender sender) {
        query(sender, () -> database.topBalances(10), (target, rows) -> {
            target.sendMessage(color("&6&lTop balances"));
            for (int i = 0; i < rows.size(); i++) {
                FbsmpDatabase.PlayerBalance row = rows.get(i);
                target.sendMessage(color("&7" + (i + 1) + ". &f" + row.name() + " &8— &6"
                        + row.formattedBalance()));
            }
        });
    }

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        if (args.length != 2) {
            player.sendMessage(color("&cUsage: /pay <online player> <amount>"));
            return;
        }
        Player recipient = Bukkit.getPlayerExact(args[0]);
        if (recipient == null) {
            player.sendMessage(color("&cThat player is not online."));
            return;
        }
        final long amount;
        try {
            amount = Money.parseMinorUnits(args[1]);
            if (amount > plugin.getConfig().getLong("economy.maximum-transfer", 100_000_000_000L)) {
                throw new IllegalArgumentException("That amount exceeds the transfer limit.");
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage(color("&c" + exception.getMessage()));
            return;
        }
        query(player, () -> database.transfer(player.getUniqueId(), player.getName(),
                recipient.getUniqueId(), recipient.getName(), amount, System.currentTimeMillis()),
                (target, completed) -> {
                    if (!completed) {
                        target.sendMessage(color("&cYou do not have enough funds."));
                        return;
                    }
                    target.sendMessage(color("&aSent &6" + Money.format(amount) + " &ato " + recipient.getName() + "."));
                    if (recipient.isOnline()) {
                        listener.notifySystem(recipient, color("&aReceived &6" + Money.format(amount) + " &afrom "
                                + target.getName() + "."), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
                    }
                });
    }

    private void setHome(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        String name = args.length == 0 ? "home" : args[0];
        if (!validName(name, plugin.getConfig().getInt("homes.max-name-length", 16))) {
            player.sendMessage(color("&cUse a home name of 1-16 letters, numbers, _ or -."));
            return;
        }
        Location location = player.getLocation().clone();
        int limit = player.hasPermission("fbsmp.homes.unlimited")
                ? Integer.MAX_VALUE : Math.max(1, plugin.getConfig().getInt("homes.default-limit", 3));
        query(player, () -> {
            List<String> existing = database.homes(player.getUniqueId());
            if (existing.stream().noneMatch(name::equalsIgnoreCase) && existing.size() >= limit) {
                return false;
            }
            database.saveHome(player.getUniqueId(), name.toLowerCase(Locale.ROOT), location, System.currentTimeMillis());
            return true;
        }, (target, saved) -> target.sendMessage(saved
                ? color("&aSaved home &f" + name + "&a.")
                : color("&cYou have reached your home limit.")));
    }

    private void home(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        String name = args.length == 0 ? "home" : args[0];
        query(player, () -> database.home(player.getUniqueId(), name), (target, saved) -> {
            if (saved.isEmpty()) {
                target.sendMessage(color("&cNo home named &f" + name + "&c exists."));
                return;
            }
            if (target instanceof Player targetPlayer) {
                teleportToSaved(targetPlayer, saved.get());
            }
        });
    }

    private void homes(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        query(player, () -> database.homes(player.getUniqueId()), (target, names) ->
                target.sendMessage(color(names.isEmpty() ? "&7You have no saved homes."
                        : "&6Homes: &f" + String.join(", ", names))));
    }

    private void deleteHome(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        if (args.length != 1) {
            player.sendMessage(color("&cUsage: /delhome <name>"));
            return;
        }
        query(player, () -> database.deleteHome(player.getUniqueId(), args[0]), (target, deleted) ->
                target.sendMessage(deleted ? color("&aHome deleted.") : color("&cHome not found.")));
    }

    private void warp(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                warpMenu.open(player);
            } else {
                sender.sendMessage(color("&7Warps are available in-game."));
            }
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Use /fbsmp warp create/delete from the console only through an in-game operator.");
            return;
        }
        query(player, () -> database.warp(args[0]), (target, saved) -> {
            if (saved.isEmpty()) {
                target.sendMessage(color("&cWarp not found."));
            } else if (target instanceof Player targetPlayer) {
                teleportToSaved(targetPlayer, saved.get());
            }
        });
    }

    private void randomTeleport(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        long cooldownMillis = Math.max(0, plugin.getConfig().getLong("teleport.cooldown-seconds", 30)) * 1000L;
        if (System.currentTimeMillis() - lastTeleportAt.getOrDefault(player.getUniqueId(), 0L) < cooldownMillis) {
            player.sendMessage(color("&cPlease wait before teleporting again."));
            return;
        }
        World world = Bukkit.getWorlds().stream().filter(candidate -> candidate.getEnvironment() == World.Environment.NORMAL)
                .findFirst().orElse(null);
        if (world == null) {
            player.sendMessage(color("&cNo Overworld is available."));
            return;
        }
        int min = Math.max(100, plugin.getConfig().getInt("teleport.random.min-radius", 500));
        int max = Math.max(min + 1, plugin.getConfig().getInt("teleport.random.max-radius", 5000));
        Optional<Location> destination = safeRandomLocation(world, min, max);
        if (destination.isEmpty()) {
            player.sendMessage(color("&cNo safe random location was found. Try again."));
            return;
        }
        lastTeleportAt.put(player.getUniqueId(), System.currentTimeMillis());
        player.teleport(destination.get());
        player.sendMessage(color("&aTeleported to a safe Overworld location."));
    }

    private void teleportRequest(CommandSender sender, String[] args, boolean here) {
        if (!(sender instanceof Player requester)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        if (args.length != 1) {
            requester.sendMessage(color(here ? "&cUsage: /tpahere <player>" : "&cUsage: /tpa <player>"));
            return;
        }
        Player recipient = Bukkit.getPlayerExact(args[0]);
        if (recipient == null) {
            requester.sendMessage(color("&cThat player is not online."));
            return;
        }
        if (recipient.equals(requester)) {
            requester.sendMessage(color("&cYou cannot request a teleport to yourself."));
            return;
        }
        long expiresAt = System.currentTimeMillis()
                + Math.max(10, plugin.getConfig().getInt("teleport.request-expiry-seconds", 60)) * 1000L;
        requestsByRecipient.put(recipient.getUniqueId(),
                new TeleportRequest(requester.getUniqueId(), recipient.getUniqueId(), here, expiresAt));
        listener.notifySystem(recipient, color("&e" + requester.getName() + (here
                ? " asks you to teleport to them. Use /tpaccept or /tpdeny."
                : " asks to teleport to you. Use /tpaccept or /tpdeny.")),
                org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING);
        requester.sendMessage(color("&aTeleport request sent to " + recipient.getName() + "."));
    }

    private void acceptTeleport(CommandSender sender) {
        if (!(sender instanceof Player recipient)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        TeleportRequest request = requestsByRecipient.remove(recipient.getUniqueId());
        if (request == null || request.expiresAt < System.currentTimeMillis()) {
            recipient.sendMessage(color("&cNo active teleport request."));
            return;
        }
        Player requester = Bukkit.getPlayer(request.requester);
        if (requester == null) {
            recipient.sendMessage(color("&cThe requester is no longer online."));
            return;
        }
        Player traveler = request.here ? recipient : requester;
        Player destinationPlayer = request.here ? requester : recipient;
        long cooldown = Math.max(0, plugin.getConfig().getLong("teleport.cooldown-seconds", 30)) * 1000L;
        if (System.currentTimeMillis() - lastTeleportAt.getOrDefault(traveler.getUniqueId(), 0L) < cooldown) {
            recipient.sendMessage(color("&cThe traveler is still on teleport cooldown."));
            return;
        }
        Location origin = traveler.getLocation().clone();
        int delaySeconds = Math.max(0, plugin.getConfig().getInt("teleport.delay-seconds", 3));
        recipient.sendMessage(color("&aRequest accepted. Teleporting in " + delaySeconds + " seconds."));
        if (requester.isOnline()) {
            requester.sendMessage(color("&aYour teleport request was accepted."));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!traveler.isOnline() || !destinationPlayer.isOnline()) {
                return;
            }
            if (plugin.getConfig().getBoolean("teleport.movement-cancels-delay", true)
                    && (traveler.getWorld() != origin.getWorld() || traveler.getLocation().distanceSquared(origin) > 0.25)) {
                traveler.sendMessage(color("&cTeleport cancelled because you moved."));
                return;
            }
            lastTeleportAt.put(traveler.getUniqueId(), System.currentTimeMillis());
            traveler.teleport(destinationPlayer.getLocation());
        }, delaySeconds * 20L);
    }

    private void denyTeleport(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        TeleportRequest request = requestsByRecipient.remove(player.getUniqueId());
        player.sendMessage(request == null ? color("&cNo active teleport request.") : color("&7Request denied."));
        if (request != null) {
            Player requester = Bukkit.getPlayer(request.requester);
            if (requester != null) {
                requester.sendMessage(color("&cYour teleport request was denied."));
            }
        }
    }

    private void privateMessage(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        if (args.length < 2) {
            player.sendMessage(color("&cUsage: /msg <player> <message>"));
            return;
        }
        Player recipient = Bukkit.getPlayerExact(args[0]);
        if (recipient == null || recipient.equals(player)) {
            player.sendMessage(color("&cChoose another online player."));
            return;
        }
        String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        query(player, () -> database.ignores(recipient.getUniqueId(), player.getUniqueId()), (target, ignored) -> {
            if (!(target instanceof Player senderPlayer)) {
                return;
            }
            if (ignored) {
                senderPlayer.sendMessage(color("&cThat player is not accepting your messages."));
                return;
            }
            senderPlayer.sendMessage(color("&d[you -> " + recipient.getName() + "] &f" + message));
            recipient.sendMessage(color("&d[" + senderPlayer.getName() + " -> you] &f" + message));
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    long now = System.currentTimeMillis();
                    database.saveReplyTarget(senderPlayer.getUniqueId(), recipient.getUniqueId(), now);
                    database.saveReplyTarget(recipient.getUniqueId(), senderPlayer.getUniqueId(), now);
                } catch (SQLException exception) {
                    plugin.getLogger().warning("Could not save private-message reply targets: " + exception.getMessage());
                }
            });
        });
    }

    private void reply(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        if (args.length == 0) {
            player.sendMessage(color("&cUsage: /reply <message>"));
            return;
        }
        query(player, () -> database.replyTarget(player.getUniqueId()), (target, lastTarget) -> {
            if (lastTarget.isEmpty()) {
                target.sendMessage(color("&cYou have no recent private-message target."));
                return;
            }
            Player recipient = Bukkit.getPlayer(lastTarget.get());
            if (recipient == null) {
                target.sendMessage(color("&cThat player is no longer online."));
                return;
            }
            String message = String.join(" ", args);
            privateMessage(target, new String[]{recipient.getName(), message});
        });
    }

    private void ignore(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        if (args.length != 1) {
            player.sendMessage(color("&cUsage: /ignore <online player>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || target.equals(player)) {
            player.sendMessage(color("&cChoose another online player."));
            return;
        }
        query(player, () -> database.toggleIgnore(player.getUniqueId(), target.getUniqueId(), System.currentTimeMillis()),
                (ignoredPlayer, nowIgnored) -> ignoredPlayer.sendMessage(color(nowIgnored
                        ? "&7You are now ignoring " + target.getName() + "."
                        : "&7You are no longer ignoring " + target.getName() + ".")));
    }

    private void stats(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        query(player, () -> {
            FbsmpDatabase.PlayerStats stats = database.stats(player.getUniqueId());
            String start = database.calendarStart();
            long startMillis;
            try {
                startMillis = Long.parseLong(start);
            } catch (NumberFormatException exception) {
                startMillis = System.currentTimeMillis();
            }
            String date = FbCalendar.date(startMillis, plugin.getConfig().getInt("calendar.day-length-minutes", 30),
                    plugin.getConfig().getInt("calendar.special-month-days", 29));
            return new StatsDisplay(stats, date);
        }, (target, display) -> {
            target.sendMessage(color("&6&lFB-SMP Stats &8— &f" + target.getName()));
            target.sendMessage(color("&7Balance: &6" + Money.format(display.stats.balanceMinor())));
            target.sendMessage(color("&7Playtime: &f" + display.stats.playtimeSeconds() / 3600 + "h "
                    + (display.stats.playtimeSeconds() % 3600) / 60 + "m"));
            target.sendMessage(color("&7Blocks placed/broken: &f" + display.stats.blocksPlaced() + "/"
                    + display.stats.blocksBroken()));
            target.sendMessage(color("&7Deaths: &f" + display.stats.deaths() + " &8| &7Date: &f" + display.date));
            target.sendMessage(color("&7Economy events: &f" + display.stats.transactions()
                    + " &8| &7Auction trades: &f" + display.stats.auctionTrades()
                    + " &8| &7Orders: &f" + display.stats.orderTrades()));
        });
    }

    private void settings(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        if (args.length == 0) {
            settingsMenu.open(player);
            return;
        }
        if (args.length != 2 || !List.of("scoreboard", "notifications", "sounds").contains(args[0].toLowerCase(Locale.ROOT))
                || !List.of("on", "off").contains(args[1].toLowerCase(Locale.ROOT))) {
            player.sendMessage(color("&cUsage: /settings <scoreboard|notifications|sounds> <on|off>"));
            return;
        }
        String key = args[0].toLowerCase(Locale.ROOT);
        String value = Boolean.toString(args[1].equalsIgnoreCase("on"));
        listener.setPreference(player, key, Boolean.parseBoolean(value), success ->
                player.sendMessage(color(success
                        ? "&a" + key + " set to " + args[1].toLowerCase(Locale.ROOT) + "."
                        : "&cCould not save that setting.")));
    }

    private void afk(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        listener.toggleAfk(player);
    }

    private void shop(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        if (args.length == 0) {
            shopMenu.openCategories(player);
            return;
        }
        if (args.length < 2 || !args[0].equalsIgnoreCase("buy")) {
            player.sendMessage(color("&cUsage: /shop buy <item> [quantity]"));
            return;
        }
        Material material = Material.matchMaterial(args[1].toUpperCase(Locale.ROOT));
        Long unitPrice = material == null ? null : shopMenu.buyPrice(material.name());
        if (material == null || unitPrice == null) {
            player.sendMessage(color("&cThat item is not currently sold by the shop."));
            return;
        }
        int quantity = 1;
        if (args.length >= 3) {
            try {
                quantity = Integer.parseInt(args[2]);
            } catch (NumberFormatException exception) {
                player.sendMessage(color("&cQuantity must be a whole number."));
                return;
            }
        }
        if (quantity < 1 || quantity > 64) {
            player.sendMessage(color("&cQuantity must be between 1 and 64."));
            return;
        }
        long total;
        try {
            total = Math.multiplyExact(unitPrice, quantity);
        } catch (ArithmeticException exception) {
            player.sendMessage(color("&cThat purchase amount is outside the supported range."));
            return;
        }
        Material purchased = material;
        int count = quantity;
        ItemStack item = new ItemStack(purchased, count);
        String itemData = Base64.getEncoder().encodeToString(item.serializeAsBytes());
        query(player, () -> database.debitAndMail(player.getUniqueId(), total, itemData,
                count + "x " + purchased.name(), System.currentTimeMillis()), (target, paid) -> {
            if (!(target instanceof Player targetPlayer)) {
                return;
            }
            if (!paid) {
                targetPlayer.sendMessage(color("&cYou do not have enough funds for " + Money.format(total) + "."));
                return;
            }
            listener.deliverMailbox(targetPlayer);
            targetPlayer.sendMessage(color("&aPurchased " + count + "x " + purchased.name() + " for &6"
                    + Money.format(total) + "&a."));
        });
    }

    private void sell(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        sellMenu.open(player);
    }

    private void auctionHouse(CommandSender sender, String[] args) {
        if (args.length > 0 && (args[0].equalsIgnoreCase("buy") || args[0].equalsIgnoreCase("cancel"))) {
            if (!(sender instanceof Player player) || args.length != 2) {
                sender.sendMessage(color("&cUsage: /ah <buy|cancel> <listing-id>"));
                return;
            }
            long listingId;
            try {
                listingId = Long.parseLong(args[1]);
                if (listingId <= 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException exception) {
                player.sendMessage(color("&cListing ID must be a positive whole number."));
                return;
            }
            if (args[0].equalsIgnoreCase("buy")) {
                query(player, () -> database.purchaseAuction(player.getUniqueId(), listingId,
                        System.currentTimeMillis()), (target, purchase) -> {
                    if (purchase == null) {
                        target.sendMessage(color("&cListing unavailable, owned by you, or not affordable."));
                        return;
                    }
                    listener.deliverMailbox(player);
                    target.sendMessage(color("&aPurchased the listing for &6"
                            + Money.format(purchase.priceMinor()) + "&a. Items were sent to your mailbox."));
                });
            } else {
                query(player, () -> database.cancelAuction(player.getUniqueId(), listingId,
                        System.currentTimeMillis()), (target, cancelled) -> {
                    if (cancelled) {
                        listener.deliverMailbox(player);
                    }
                    target.sendMessage(color(cancelled ? "&aListing cancelled; item returned by player mail."
                            : "&cYou cannot cancel that listing."));
                });
            }
            return;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                auctionMenu.open(player);
            } else {
                sender.sendMessage(color("&7Auction listings are available in-game."));
            }
            return;
        }
        query(sender, () -> database.openAuctions(System.currentTimeMillis(), 20), (target, listings) -> {
            if (listings.isEmpty()) {
                target.sendMessage(color("&7No active auction listings."));
                return;
            }
            target.sendMessage(color("&6&lAuction house &8— &7Use &f/ah buy <id>"));
            for (FbsmpDatabase.AuctionListing listing : listings) {
                String itemName;
                try {
                    ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(listing.itemData()));
                    itemName = item.getType().name() + " x" + listing.quantity();
                } catch (IllegalArgumentException exception) {
                    itemName = "Unreadable listing item";
                }
                target.sendMessage(color("&f#" + listing.id() + " &7" + itemName + " &8— &6"
                        + Money.format(listing.priceMinor()) + " &8| &7seller " + listing.seller()));
            }
        });
    }

    private void auctionSell(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            sender.sendMessage(color("&cUsage: /ausell <price> (hold the item to list)"));
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            player.sendMessage(color("&cHold the item you want to list."));
            return;
        }
        long price;
        try {
            price = Money.parseMinorUnits(args[0]);
            long minimum = Money.parseMinorUnits(featureConfig("auction").getString("minimum-price", "0.01"));
            if (price < minimum) {
                throw new IllegalArgumentException("The listing is below the configured minimum.");
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage(color("&c" + exception.getMessage()));
            return;
        }
        ItemStack listed = held.clone();
        String itemData = Base64.getEncoder().encodeToString(listed.serializeAsBytes());
        int quantity = listed.getAmount();
        held.setAmount(0);
        player.saveData();
        long expiresAt = System.currentTimeMillis()
                + Math.max(1, featureConfig("auction").getLong("listing-expiration-hours", 72)) * 3_600_000L;
        UUID sellerId = player.getUniqueId();
        String sellerName = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long id = -1;
            try {
                id = database.createAuction(sellerId, itemData, quantity, price, expiresAt,
                        System.currentTimeMillis());
            } catch (Exception exception) {
                plugin.getLogger().warning("Auction listing failed for " + sellerId + ": " + exception.getMessage());
            }
            if (id <= 0) {
                try {
                    database.queueMailboxItem(sellerId, itemData, System.currentTimeMillis());
                } catch (SQLException exception) {
                    plugin.getLogger().severe("Could not return failed auction item for " + sellerId + ": "
                            + exception.getMessage());
                }
            }
            long listingId = id;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player onlineSeller = Bukkit.getPlayer(sellerId);
                if (onlineSeller == null) {
                    return;
                }
                if (listingId > 0) {
                    onlineSeller.sendMessage(color("&aCreated auction listing &f#" + listingId + " &afor &6"
                            + Money.format(price) + "&a."));
                } else {
                    listener.deliverMailbox(onlineSeller);
                    onlineSeller.sendMessage(color("&cListing limit reached or the listing could not be created. "
                            + "Your item was returned by player mail."));
                }
            });
        });
    }

    private void loan(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        if (loanService == null) {
            player.sendMessage(color("&cThe loan system is not available right now."));
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("vu") || args[0].equalsIgnoreCase("info")) {
            showLoanInfo(player);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("repay")) {
            long amount = args.length >= 2 ? parseAmount(player, args[1]) : -1;
            if (args.length < 2) {
                amount = Long.MAX_VALUE; // repay as much as possible
            }
            if (amount == -1) {
                return;
            }
            final long requested = amount;
            query(player, () -> {
                long outstanding = database.loanOutstanding(player.getUniqueId());
                long payable = Math.min(requested, outstanding);
                if (payable <= 0) {
                    return 0L;
                }
                return loanService.repay(player.getUniqueId(), payable);
            }, (target, paid) -> {
                if (paid < 0) {
                    target.sendMessage(color("&cInsufficient funds."));
                } else if (paid == 0) {
                    target.sendMessage(color("&cNo active loan balance was found."));
                } else {
                    target.sendMessage(color("&aLoan repayment posted: &6" + Money.format(paid)
                            + "&a. Remaining: &6" + Money.format(Math.max(0, currentOutstanding(target)
                            - paid))));
                    listener.deliverMailbox(player);
                }
            });
            return;
        }
        if (!sub.equals("take") && !sub.equals("borrow")) {
            player.sendMessage(color("&cUsage: /loan <take|repay|info> [amount]"));
            return;
        }
        if (args.length != 2) {
            player.sendMessage(color("&cUsage: /loan take <amount>"));
            return;
        }
        long amount = parseAmount(player, args[1]);
        if (amount <= 0) {
            return;
        }
        if (amount > loanService.maximumOutstanding()) {
            player.sendMessage(color("&cThat request exceeds the configured loan limit of &6"
                    + Money.format(loanService.maximumOutstanding()) + "&c."));
            return;
        }
        boolean bypass = player.hasPermission("fbsmp.loan.limit.bypass");
        query(player, () -> {
            if (loanService.requireClearTimeoutToBorrow()
                    && !bypass && loanService.isTimedOut(player.getUniqueId())) {
                return -2L;
            }
            return loanService.issue(player.getUniqueId(), amount, bypass);
        }, (target, loanId) -> {
            if (loanId == -2L) {
                target.sendMessage(color("&cYou are currently timed out. Clear your overdue loan first."));
            } else if (loanId < 0) {
                target.sendMessage(color("&cLoan limit reached or the loan could not be issued."));
            } else {
                LoanService.LoanTerms terms = loanService.termsFor(amount);
                target.sendMessage(color("&aLoan #" + loanId + " issued: &6" + Money.format(amount)
                        + " &7principal, &f" + Money.format(terms.interestMinor()) + " &7interest, due &f"
                        + loanService.calendar().format(
                                loanService.calendar().fromDayIndex(terms.dueDayIndex())) + "&a."));
                loanService.audit(player.getUniqueId(), player.getName(), "LOAN_ISSUE",
                        "loan#" + loanId, "", "principal=" + amount, "player request");
            }
        });
    }

    private long currentOutstanding(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return 0;
        }
        try {
            return database.loanOutstanding(player.getUniqueId());
        } catch (Exception exception) {
            return 0;
        }
    }

    private long parseAmount(Player player, String raw) {
        try {
            long amount = Money.parseMinorUnits(raw);
            if (amount <= 0) {
                player.sendMessage(color("&cAmount must be positive."));
                return -1;
            }
            return amount;
        } catch (IllegalArgumentException exception) {
            player.sendMessage(color("&c" + exception.getMessage()));
            return -1;
        }
    }

    private void showLoanInfo(Player player) {
        query(player, () -> {
            List<FbsmpDatabase.LoanRow> loans = database.loansFor(player.getUniqueId());
            long today = loanService.todayIndex();
            List<String> lines = new ArrayList<>();
            for (FbsmpDatabase.LoanRow loan : loans) {
                if ("PAID".equals(loan.status())) {
                    continue;
                }
                long daysLeft = loan.dueDayIndex() - today;
                lines.add("&7#" + loan.id() + " &f" + Money.format(loan.remainingMinor()) + " &7"
                        + loan.status().toLowerCase(Locale.ROOT) + " &8| due &f"
                        + loanService.calendar().format(loanService.calendar().fromDayIndex(loan.dueDayIndex()))
                        + " &8(" + (daysLeft >= 0 ? daysLeft + "d left" : Math.abs(daysLeft) + "d overdue") + ")");
            }
            FbsmpDatabase.LoanTimeout timeout = database.activeTimeout(player.getUniqueId(),
                    System.currentTimeMillis());
            return new Object[]{lines, timeout};
        }, (target, payload) -> {
            @SuppressWarnings("unchecked")
            List<String> lines = (List<String>) payload[0];
            FbsmpDatabase.LoanTimeout timeout = (FbsmpDatabase.LoanTimeout) payload[1];
            if (lines.isEmpty()) {
                target.sendMessage(color("&6Loans: &fno active loans."));
            } else {
                target.sendMessage(color("&6Active loans:"));
                lines.forEach(line -> target.sendMessage(color(line)));
            }
            if (timeout != null) {
                long seconds = Math.max(0, (timeout.expiresAt() - System.currentTimeMillis()) / 1000);
                target.sendMessage(color("&cTimed out for &f" + (seconds / 60) + "m " + (seconds % 60)
                        + "s&c: " + timeout.reason()));
            }
            target.sendMessage(color("&7Use &f/loan take <amount> &7or &f/loan repay [amount]&7."));
        });
    }

    private void mortgage(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        YamlConfiguration config = featureConfig("mortgages");
        if (args.length == 0) {
            mortgageMenu.open(player);
            return;
        }
        if (args[0].equalsIgnoreCase("pay") && args.length == 1) {
            long interval = Math.max(1, config.getLong("payment-interval-days", 30))
                    * (long) plugin.getConfig().getInt("calendar.day-length-minutes", 30) * 60_000L;
            query(player, () -> database.payMortgageInstallment(player.getUniqueId(),
                    System.currentTimeMillis(), interval), (target, payment) -> {
                if (payment == null) {
                    target.sendMessage(color("&cNo active mortgage was found."));
                } else if (payment.paidMinor() < 0) {
                    target.sendMessage(color("&cNot enough funds for the due installment."));
                } else if (payment.paidMinor() == 0) {
                    target.sendMessage(color("&7No installment is due yet."));
                } else {
                    target.sendMessage(color("&aMortgage payment for &f" + payment.assetKey() + ": &6"
                            + Money.format(payment.paidMinor()) + " &7remaining &f"
                            + Money.format(payment.remainingMinor())));
                }
            });
            return;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("buy")) {
            player.sendMessage(color("&cUsage: /mort <buy <asset>|pay>"));
            return;
        }
        ConfigurationSection properties = config.getConfigurationSection("available-properties");
        String asset = args[1];
        String priceText = properties == null ? null : properties.getString(asset);
        if (priceText == null) {
            player.sendMessage(color("&cThat asset is not configured."));
            return;
        }
        try {
            long price = Money.parseMinorUnits(priceText);
            BigDecimal downPercent = BigDecimal.valueOf(config.getDouble("down-payment-percent", 20));
            long downPayment = BigDecimal.valueOf(price).multiply(downPercent)
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING).longValueExact();
            long financed = price - downPayment;
            if (downPayment < 0 || financed <= 0) {
                throw new IllegalArgumentException("Down payment must be below the asset price.");
            }
            long interest = percentage(financed, config.getDouble("interest-rate-percent", 5));
            int installments = Math.max(1, Math.min(120, config.getInt("installments", 12)));
            long interval = Math.max(1, config.getLong("payment-interval-days", 30))
                    * (long) plugin.getConfig().getInt("calendar.day-length-minutes", 30) * 60_000L;
            query(player, () -> database.createMortgage(player.getUniqueId(), asset, price, downPayment,
                    interest, installments, interval, System.currentTimeMillis()), (target, created) ->
                    target.sendMessage(color(created
                            ? "&aMortgage created for &f" + asset + "&a. Down payment: &6"
                                + Money.format(downPayment) + "&a."
                            : "&cAsset unavailable or you do not have enough for the down payment.")));
        } catch (IllegalArgumentException | ArithmeticException exception) {
            player.sendMessage(color("&cThat mortgage offer is invalid."));
        }
    }

    private void mortgageAccount(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        query(player, () -> database.mortgageSummary(player.getUniqueId()), (target, summary) -> {
            if (summary.accounts().isEmpty()) {
                target.sendMessage(color("&7You have no active mortgages."));
                return;
            }
            target.sendMessage(color("&6Mortgage balance: &f" + Money.format(summary.outstandingMinor())));
            for (FbsmpDatabase.MortgageAccount account : summary.accounts()) {
                target.sendMessage(color("&f" + account.assetKey() + " &8— &6"
                        + Money.format(account.remainingMinor()) + " &7over " + account.installmentsLeft()
                        + " installments; next due " + FbCalendar.date(account.nextDueAt(),
                        plugin.getConfig().getInt("calendar.day-length-minutes", 30), 29)));
            }
        });
    }

    private void gift(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
            PendingGift gift = pendingGifts.get(player.getUniqueId());
            if (gift == null || !gift.accepted || gift.expiresAt < System.currentTimeMillis()) {
                player.sendMessage(color("&cNo accepted gift is awaiting confirmation."));
                return;
            }
            pendingGifts.remove(player.getUniqueId(), gift);
            Player recipient = Bukkit.getPlayer(gift.recipient);
            String recipientName = recipient == null ? gift.recipientName : recipient.getName();
            query(player, () -> database.transfer(player.getUniqueId(), player.getName(), gift.recipient,
                    recipientName, gift.amountMinor, "GIFT", "Confirmed currency gift",
                    System.currentTimeMillis()), (target, sent) -> {
                if (!sent) {
                    target.sendMessage(color("&cGift failed: insufficient funds."));
                } else {
                    target.sendMessage(color("&aGift sent: &6" + Money.format(gift.amountMinor) + "&a."));
                    if (recipient != null && recipient.isOnline()) {
                        recipient.sendMessage(color("&aReceived a confirmed gift of &6"
                                + Money.format(gift.amountMinor) + "&a."));
                    }
                }
            });
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("accept")) {
            Player senderPlayer = Bukkit.getPlayerExact(args[1]);
            PendingGift gift = senderPlayer == null ? null : pendingGifts.get(senderPlayer.getUniqueId());
            if (gift == null || !gift.recipient.equals(player.getUniqueId())
                    || gift.expiresAt < System.currentTimeMillis()) {
                player.sendMessage(color("&cNo current gift request from that player."));
                return;
            }
            pendingGifts.put(senderPlayer.getUniqueId(), new PendingGift(gift.recipient, gift.recipientName,
                    gift.amountMinor, gift.expiresAt, true));
            player.sendMessage(color("&aGift accepted. The sender must use &f/gift confirm&a."));
            senderPlayer.sendMessage(color("&a" + player.getName() + " accepted your gift. Use &f/gift confirm&a."));
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            pendingGifts.remove(player.getUniqueId());
            player.sendMessage(color("&7Pending gift cancelled."));
            return;
        }
        if (args.length != 2) {
            player.sendMessage(color("&cUsage: /gift <online player> <amount> | /gift accept <sender> | /gift confirm | /gift cancel"));
            return;
        }
        Player recipient = Bukkit.getPlayerExact(args[0]);
        if (recipient == null || recipient.equals(player)) {
            player.sendMessage(color("&cChoose another online player."));
            return;
        }
        long amount;
        try {
            amount = Money.parseMinorUnits(args[1]);
            long maxGift = Money.parseMinorUnits(featureConfig("gifts").getString(
                    "maximum-currency-gift", "100000.00"));
            if (amount > maxGift) {
                player.sendMessage(color("&cThat amount exceeds the gift limit of &6" + Money.format(maxGift) + "&c."));
                return;
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage(color("&c" + exception.getMessage()));
            return;
        }
        PendingGift gift = new PendingGift(recipient.getUniqueId(), recipient.getName(), amount,
                System.currentTimeMillis() + 60_000L, false);
        pendingGifts.put(player.getUniqueId(), gift);
        player.sendMessage(color("&eGift request created. " + recipient.getName()
                + " must use &f/gift accept " + player.getName() + "&e; then confirm with &f/gift confirm&e."));
        recipient.sendMessage(color("&e" + player.getName() + " offers you &6" + Money.format(amount)
                + "&e. Use &f/gift accept " + player.getName() + "&e to accept."));
    }

    private void orders(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            query(player, () -> database.openOrders(System.currentTimeMillis(), 30), (target, orders) -> {
                if (orders.isEmpty()) {
                    target.sendMessage(color("&7No active community orders."));
                    return;
                }
                target.sendMessage(color("&6&lCommunity orders"));
                for (FbsmpDatabase.CommunityOrder order : orders) {
                    target.sendMessage(color("&f#" + order.id() + " &7" + order.quantity() + "x "
                            + order.itemKey() + " &8— &6" + Money.format(order.rewardMinor()) + " &8| &f"
                            + order.status()));
                }
            });
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("create") && args.length == 4) {
            Material item = Material.matchMaterial(args[1].toUpperCase(Locale.ROOT));
            int quantity;
            long reward;
            try {
                quantity = Integer.parseInt(args[2]);
                reward = Money.parseMinorUnits(args[3]);
            } catch (IllegalArgumentException exception) {
                player.sendMessage(color("&cUsage: /orders create <item> <quantity> <reward>"));
                return;
            }
            if (item == null || item.isAir() || quantity < 1 || quantity > 64) {
                player.sendMessage(color("&cUse a valid item and a quantity from 1 to 64."));
                return;
            }
            long expiresAt = System.currentTimeMillis()
                    + Math.max(1, featureConfig("orders").getLong("default-expiration-hours", 168))
                    * 3_600_000L;
            query(player, () -> database.createOrder(player.getUniqueId(), item.name(), quantity,
                    reward, expiresAt, System.currentTimeMillis()), (target, created) ->
                    target.sendMessage(color(created
                            ? "&aOrder posted. The reward is held in escrow until delivery is accepted."
                            : "&cOrder could not be posted. Check your balance and open-order limit.")));
            return;
        }
        if (args.length != 2) {
            player.sendMessage(color("&cUsage: /orders <list|create|claim|deliver|complete|cancel> [id ...]"));
            return;
        }
        long id;
        try {
            id = Long.parseLong(args[1]);
            if (id <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            player.sendMessage(color("&cOrder ID must be a positive whole number."));
            return;
        }
        switch (action) {
            case "claim" -> query(player, () -> database.claimOrder(player.getUniqueId(), id,
                    System.currentTimeMillis()), (target, claimed) ->
                    target.sendMessage(color(claimed ? "&aOrder claimed. Deliver the listed item with /orders deliver "
                            + id + "&a." : "&cThat order cannot be claimed.")));
            case "complete" -> query(player, () -> database.completeOrder(player.getUniqueId(), id,
                    System.currentTimeMillis()), (target, completed) ->
                    target.sendMessage(color(completed ? "&aDelivery approved; reward paid to the supplier."
                            : "&cThat order is not awaiting your approval.")));
            case "cancel" -> query(player, () -> database.cancelOrder(player.getUniqueId(), id,
                    System.currentTimeMillis()), (target, cancelled) ->
                    target.sendMessage(color(cancelled ? "&aOrder cancelled; escrow refunded."
                            : "&cOnly your unclaimed orders can be cancelled.")));
            case "deliver" -> deliverOrder(player, id);
            default -> player.sendMessage(color("&cUnknown order action."));
        }
    }

    private void deliverOrder(Player player, long orderId) {
        query(player, () -> database.openOrders(System.currentTimeMillis(), 100).stream()
                .filter(order -> order.id() == orderId).findFirst().orElse(null), (target, order) -> {
            if (order == null || !"CLAIMED".equals(order.status())
                    || !player.getUniqueId().equals(order.claimer())) {
                target.sendMessage(color("&cThat is not an active order claimed by you."));
                return;
            }
            Material material = Material.matchMaterial(order.itemKey());
            if (material == null || material.isAir()) {
                target.sendMessage(color("&cThe requested item is invalid. Contact an administrator."));
                return;
            }
            ItemStack required = new ItemStack(material);
            int available = 0;
            for (ItemStack stack : player.getInventory().getStorageContents()) {
                if (stack != null && stack.isSimilar(required)) {
                    available += stack.getAmount();
                }
            }
            if (available < order.quantity()) {
                target.sendMessage(color("&cYou need " + order.quantity() + " plain "
                        + material.name() + " to deliver this order."));
                return;
            }
            int remaining = order.quantity();
            for (int slot = 0; slot < player.getInventory().getStorageContents().length && remaining > 0; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack == null || !stack.isSimilar(required)) {
                    continue;
                }
                int take = Math.min(remaining, stack.getAmount());
                if (take == stack.getAmount()) {
                    player.getInventory().setItem(slot, null);
                } else {
                    stack.setAmount(stack.getAmount() - take);
                    player.getInventory().setItem(slot, stack);
                }
                remaining -= take;
            }
            player.saveData();
            listener.markActive(player);
            ItemStack delivery = new ItemStack(material, order.quantity());
            String itemData = Base64.getEncoder().encodeToString(delivery.serializeAsBytes());
            UUID claimerId = player.getUniqueId();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean delivered = false;
                try {
                    delivered = database.deliverOrder(claimerId, orderId, itemData, System.currentTimeMillis());
                } catch (SQLException exception) {
                    plugin.getLogger().warning("Order delivery failed for order #" + orderId + ": "
                            + exception.getMessage());
                }
                if (!delivered) {
                    try {
                        database.queueMailboxItem(claimerId, itemData, System.currentTimeMillis());
                    } catch (SQLException exception) {
                        plugin.getLogger().severe("Could not return failed order delivery to " + claimerId + ": "
                                + exception.getMessage());
                    }
                }
                boolean completedDelivery = delivered;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player onlineClaimer = Bukkit.getPlayer(claimerId);
                    if (onlineClaimer != null) {
                        if (completedDelivery) {
                            onlineClaimer.sendMessage(color("&aDelivery recorded. The requester can now approve it."));
                        } else {
                            listener.deliverMailbox(onlineClaimer);
                            onlineClaimer.sendMessage(color("&cOrder delivery failed. Your items were returned."));
                        }
                    }
                    if (completedDelivery) {
                        Player requester = Bukkit.getPlayer(order.requester());
                        if (requester != null) {
                            listener.deliverMailbox(requester);
                            requester.sendMessage(color("&eOrder #" + orderId + " was delivered. Use &f/orders complete "
                                    + orderId + "&e to approve payment."));
                        }
                    }
                });
            });
        });
    }

    private YamlConfiguration featureConfig(String name) {
        File file = new File(plugin.getDataFolder(), name + ".yml");
        return YamlConfiguration.loadConfiguration(file);
    }

    private static long percentage(long amountMinor, double percent) {
        if (!Double.isFinite(percent) || percent < 0) {
            throw new IllegalArgumentException("Percentage must be zero or greater.");
        }
        return BigDecimal.valueOf(amountMinor).multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private void admin(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("version")) {
            sender.sendMessage(color("&6FB-SMP &f" + plugin.getDescription().getVersion() + " &7| Paper + Java "
                    + Runtime.version().feature()));
            return;
        }
        if (args[0].equalsIgnoreCase("calendar")) {
            query(sender, () -> {
                long start;
                try {
                    start = Long.parseLong(database.calendarStart());
                } catch (NumberFormatException exception) {
                    start = System.currentTimeMillis();
                }
                return FbCalendar.date(start, plugin.getConfig().getInt("calendar.day-length-minutes", 30),
                        plugin.getConfig().getInt("calendar.special-month-days", 29));
            }, (target, date) -> target.sendMessage(color("&6FB calendar: &f" + date)));
            return;
        }
        if (!sender.hasPermission("fbsmp.admin")) {
            sender.sendMessage(color("&cYou do not have permission to use that command."));
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "backup" -> {
                sender.sendMessage(color("&7Creating a consistent SQLite backup..."));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        plugin.backupDatabase();
                        Bukkit.getScheduler().runTask(plugin,
                                () -> sender.sendMessage(color("&aDatabase backup completed.")));
                    } catch (Exception exception) {
                        plugin.getLogger().warning("Backup failed: " + exception.getMessage());
                        Bukkit.getScheduler().runTask(plugin,
                                () -> sender.sendMessage(color("&cBackup failed. Check the server log.")));
                    }
                });
            }
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage(color("&aFB-SMP config.yml reloaded."));
            }
            case "debug", "database" -> query(sender, database::adminSnapshot, (target, snapshot) ->
                    target.sendMessage(color("&7SQLite database &8| &f" + snapshot.playerCount()
                            + " player records &8| &f" + snapshot.transactionCount() + " transactions &8| &f"
                            + Money.format(snapshot.totalBalanceMinor()) + " total balance")));
            case "player" -> adminPlayer(sender, args, false);
            case "economy" -> adminPlayer(sender, args, true);
            case "warp" -> adminWarp(sender, args);
            case "loan" -> adminLoan(sender, args);
            case "audit" -> query(sender, () -> database.recentAuditLog(50), (target, rows) -> {
                target.sendMessage(color("&6Recent audit entries: &f" + rows.size()));
                for (FbsmpDatabase.AuditRow row : rows) {
                    target.sendMessage(color("&7" + FbCalendar.isoTimestamp(row.createdAt()) + " &f"
                            + row.actorName() + " &7" + row.action() + " &f" + row.target()
                            + (row.reason().isBlank() ? "" : " &8(" + row.reason() + ")")));
                }
            });
            default -> sender.sendMessage(color("&7Usage: /fbsmp <version|backup|reload|debug|database|player|economy|calendar|warp|loan|audit>"));
        }
    }

    private void adminLoan(CommandSender sender, String[] args) {
        if (loanService == null) {
            sender.sendMessage(color("&cThe loan system is not available right now."));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(color("&7Usage: /fbsmp loan <list|info|forgive|settle|adjust|releasetimeouts> [args]"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> query(sender, database::activeLoans, (target, loans) -> {
                target.sendMessage(color("&6Active loans: &f" + loans.size()));
                for (FbsmpDatabase.LoanRow loan : loans) {
                    target.sendMessage(color("&7#" + loan.id() + " &f" + loan.playerUuid() + " &7"
                            + Money.format(loan.remainingMinor()) + " " + loan.status()));
                }
            });
            case "info" -> {
                long loanId = parseLong(sender, args, 2);
                if (loanId < 0) {
                    return;
                }
                query(sender, () -> database.loanById(loanId), (target, loan) -> {
                    if (loan == null) {
                        target.sendMessage(color("&cNo such loan."));
                    } else {
                        target.sendMessage(color("&6Loan #" + loan.id() + " &f" + loan.playerUuid()
                                + " &7status &f" + loan.status() + " &7remaining &f"
                                + Money.format(loan.remainingMinor()) + " &7original &f"
                                + Money.format(loan.originalRemainingMinor()) + " &7term &f"
                                + loan.termDays() + "d"));
                    }
                });
            }
            case "forgive" -> {
                long loanId = parseLong(sender, args, 2);
                if (loanId < 0) {
                    return;
                }
                String reason = args.length >= 4 ? args[3] : "admin forgive";
                query(sender, () -> {
                    boolean changed = database.forgiveLoan(loanId, reason, System.currentTimeMillis());
                    loanService.audit(null, sender.getName(), "LOAN_FORGIVE", "loan#" + loanId, "", "", reason);
                    return changed;
                }, (target, changed) -> target.sendMessage(color(changed
                        ? "&aLoan forgiven and timeouts released." : "&cLoan not found or already settled.")));
            }
            case "settle" -> {
                long loanId = parseLong(sender, args, 2);
                if (loanId < 0) {
                    return;
                }
                String reason = args.length >= 4 ? args[3] : "admin settle";
                query(sender, () -> {
                    boolean changed = database.settleLoan(loanId, reason, System.currentTimeMillis());
                    loanService.audit(null, sender.getName(), "LOAN_SETTLE", "loan#" + loanId, "", "", reason);
                    return changed;
                }, (target, changed) -> target.sendMessage(color(changed
                        ? "&aLoan settled and timeouts released." : "&cLoan not found or already settled.")));
            }
            case "adjust" -> {
                if (args.length < 4) {
                    sender.sendMessage(color("&7Usage: /fbsmp loan adjust <loanId> <newRemaining> [reason]"));
                    return;
                }
                long loanId = parseLong(sender, args, 2);
                long newRemaining = parseMinor(sender, args[3]);
                if (loanId < 0 || newRemaining < 0) {
                    return;
                }
                String reason = args.length >= 5 ? args[4] : "admin adjust";
                query(sender, () -> {
                    boolean changed = database.adjustLoanRemaining(loanId, newRemaining, reason,
                            System.currentTimeMillis());
                    loanService.audit(null, sender.getName(), "LOAN_ADJUST", "loan#" + loanId, "",
                            "remaining=" + newRemaining, reason);
                    return changed;
                }, (target, changed) -> target.sendMessage(color(changed
                        ? "&aLoan remaining balance adjusted." : "&cLoan not found.")));
            }
            case "releasetimeouts" -> {
                if (args.length < 3) {
                    sender.sendMessage(color("&7Usage: /fbsmp loan releasetimeouts <player>"));
                    return;
                }
                org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(args[2]);
                query(sender, () -> database.releaseTimeoutsForPaidLoans(offline.getUniqueId(),
                        System.currentTimeMillis()), (target, released) -> {
                    loanService.audit(null, sender.getName(), "LOAN_TIMEOUT_RELEASE", args[2],
                            "", "", "manual release");
                    target.sendMessage(color("&aReleased &f" + released + "&a timeout(s)."));
                });
            }
            default -> sender.sendMessage(color("&7Usage: /fbsmp loan <list|info|forgive|settle|adjust|releasetimeouts> [args]"));
        }
    }

    private long parseLong(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            sender.sendMessage(color("&cMissing loan id."));
            return -1;
        }
        try {
            return Long.parseLong(args[index]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(color("&cLoan id must be a number."));
            return -1;
        }
    }

    private long parseMinor(CommandSender sender, String raw) {
        try {
            return Money.parseMinorUnits(raw);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(color("&c" + exception.getMessage()));
            return -1;
        }
    }

    private void adminPlayer(CommandSender sender, String[] args, boolean economyOnly) {
        if (args.length != 2 || !validName(args[1], 16)) {
            sender.sendMessage(color(economyOnly
                    ? "&cUsage: /fbsmp economy <player>"
                    : "&cUsage: /fbsmp player <player>"));
            return;
        }
        String playerName = args[1];
        Player online = Bukkit.getPlayerExact(playerName);
        query(sender, () -> online == null ? database.playerUuidByName(playerName)
                        : Optional.of(online.getUniqueId()),
                (target, playerId) -> {
                    if (playerId.isEmpty()) {
                        target.sendMessage(color("&cNo stored player named &f" + playerName + "&c."));
                        return;
                    }
                    if (economyOnly) {
                        query(target, () -> database.balance(playerId.get()), (resultTarget, amount) ->
                                resultTarget.sendMessage(color("&6" + playerName + "'s balance: &f"
                                        + Money.format(amount))));
                    } else {
                        query(target, () -> database.stats(playerId.get()), (resultTarget, stats) -> {
                            resultTarget.sendMessage(color("&6Player record: &f" + playerName));
                            resultTarget.sendMessage(color("&7Balance: &6" + Money.format(stats.balanceMinor())
                                    + " &8| &7Playtime: &f" + stats.playtimeSeconds() / 3600 + "h"));
                            resultTarget.sendMessage(color("&7Blocks: &f" + stats.blocksPlaced() + " placed / "
                                    + stats.blocksBroken() + " broken &8| &7Deaths: &f" + stats.deaths()));
                            resultTarget.sendMessage(color("&7Economy events: &f" + stats.transactions()
                                    + " &8| &7Auction trades: &f" + stats.auctionTrades()
                                    + " &8| &7Order trades: &f" + stats.orderTrades()));
                        });
                    }
                });
    }

    private void moderate(CommandSender sender, String[] args, String action) {
        if (!sender.hasPermission("fbsmp.moderate")) {
            sender.sendMessage(color("&cYou do not have permission to use this moderation command."));
            return;
        }
        boolean isKick = action.equals("KICK");
        if (args.length < 1) {
            sender.sendMessage(color(switch (action) {
                case "BAN" -> "&cUsage: /ban <player> [duration|permanent] [reason]";
                case "KICK" -> "&cUsage: /kick <online-player> [reason]";
                default -> "&cUsage: /timeout <player> [duration] [reason]";
            }));
            return;
        }
        String targetName = args[0];
        if (!validName(targetName, 16)) {
            sender.sendMessage(color("&cUse a valid player name."));
            return;
        }
        Player onlineTarget = Bukkit.getPlayerExact(targetName);
        if (isKick && onlineTarget == null) {
            sender.sendMessage(color("&cKick requires the target to be online."));
            return;
        }
        int reasonIndex;
        Long expiresAt = null;
        if (action.equals("BAN")) {
            if (args.length > 1 && isDuration(args[1])) {
                expiresAt = durationExpiration(args[1], System.currentTimeMillis());
                reasonIndex = 2;
            } else if (args.length > 1 && args[1].equalsIgnoreCase("permanent")) {
                reasonIndex = 2;
            } else {
                reasonIndex = 1;
            }
        } else if (action.equals("TIMEOUT")) {
            if (args.length > 1 && isDuration(args[1])) {
                try {
                    expiresAt = durationExpiration(args[1], System.currentTimeMillis());
                } catch (IllegalArgumentException exception) {
                    sender.sendMessage(color("&c" + exception.getMessage()));
                    return;
                }
                reasonIndex = 2;
            } else {
                int minutes = Math.max(1, featureConfig("moderation").getInt("default-timeout-minutes", 10));
                try {
                    expiresAt = durationExpiration(minutes + "m", System.currentTimeMillis());
                } catch (IllegalArgumentException exception) {
                    sender.sendMessage(color("&c" + exception.getMessage()));
                    return;
                }
                reasonIndex = 1;
            }
        } else {
            reasonIndex = 1;
        }
        String reason = reasonIndex >= args.length ? "No reason specified."
                : String.join(" ", java.util.Arrays.copyOfRange(args, reasonIndex, args.length));
        reason = ChatColor.stripColor(reason).replaceAll("[\\p{Cntrl}&&[^\\t]]", "").trim();
        int maximumReasonLength = Math.max(1, featureConfig("moderation").getInt("maximum-reason-length", 256));
        if (reason.length() > maximumReasonLength) {
            sender.sendMessage(color("&cReason must be " + maximumReasonLength + " characters or fewer."));
            return;
        }
        final String moderationReason = reason.isBlank() ? "No reason specified." : reason;
        final Long moderationExpiry = expiresAt;
        final UUID staffId = sender instanceof Player staff
                ? staff.getUniqueId() : UUID.nameUUIDFromBytes("FB-SMP-CONSOLE".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final String actionWord = action;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                UUID targetId = onlineTarget != null
                        ? onlineTarget.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();
                long recordId = database.recordModeration(targetId, staffId, actionWord,
                        moderationReason, moderationExpiry, System.currentTimeMillis());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String staffName = sender instanceof Player staff ? staff.getName() : "Console";
                    plugin.getLogger().info(staffName + " recorded " + actionWord + " for " + targetName
                            + " (record " + recordId + "): " + moderationReason);
                    sender.sendMessage(color("&a" + actionWord.toLowerCase(Locale.ROOT) + " recorded for &f"
                            + targetName + "&a."));
                    if ((actionWord.equals("BAN") || isKick) && onlineTarget != null && onlineTarget.isOnline()) {
                        onlineTarget.kickPlayer(actionWord.equals("BAN")
                                ? "You are banned from FB-SMP. Reason: " + moderationReason
                                : "You were kicked from FB-SMP. Reason: " + moderationReason);
                    }
                    for (Player staff : Bukkit.getOnlinePlayers()) {
                        if (staff.hasPermission("fbsmp.moderate")) {
                            staff.sendMessage(color("&8[&cModeration&8] &f" + staffName + " &7" + actionWord
                                    + " &f" + targetName + " &8— &7" + moderationReason));
                        }
                    }
                });
            } catch (Exception exception) {
                plugin.getLogger().warning("Could not record " + actionWord + " for " + targetName + ": "
                        + exception.getMessage());
                Bukkit.getScheduler().runTask(plugin,
                        () -> sender.sendMessage(color("&cModeration record could not be saved.")));
            }
        });
    }

    private Long durationExpiration(String duration, long now) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([1-9][0-9]*)([smhdw])",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(duration);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Duration must use seconds, minutes, hours, days or weeks (e.g. 30m, 2d).");
        }
        try {
            long amount = Long.parseLong(matcher.group(1));
            long multiplier = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                case "w" -> 604_800_000L;
                default -> throw new IllegalArgumentException("Unsupported duration unit.");
            };
            long milliseconds = Math.multiplyExact(amount, multiplier);
            long maximumDays = Math.max(1, featureConfig("moderation").getLong("maximum-duration-days", 3650));
            if (milliseconds > Math.multiplyExact(maximumDays, 86_400_000L)) {
                throw new IllegalArgumentException("Duration exceeds the configured maximum.");
            }
            return Math.addExact(now, milliseconds);
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("Duration is outside the supported range.");
        }
    }

    private static boolean isDuration(String value) {
        return value.matches("[1-9][0-9]*[smhdwSMHDW]");
    }

    private void adminWarp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cWarp create/set/delete requires an in-game operator."));
            return;
        }
        if (args.length < 3) {
            player.sendMessage(color("&cUsage: /fbsmp warp <create|set|delete> <name>"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        String name = args[2].toLowerCase(Locale.ROOT);
        if (!validName(name, 32)) {
            player.sendMessage(color("&cWarp name must use letters, numbers, _ or - (up to 32 characters)."));
            return;
        }
        if (action.equals("delete")) {
            query(player, () -> database.deleteWarp(name), (target, deleted) ->
                    target.sendMessage(color(deleted ? "&aWarp deleted." : "&cWarp not found.")));
        } else if (action.equals("create") || action.equals("set")) {
            Location location = player.getLocation().clone();
            query(player, () -> {
                database.saveWarp(name, location, player.getUniqueId(), System.currentTimeMillis());
                return true;
            }, (target, ignored) -> target.sendMessage(color("&aWarp &f" + name + " &asaved.")));
        } else {
            player.sendMessage(color("&cUsage: /fbsmp warp <create|set|delete> <name>"));
        }
    }

    private void teleportToSaved(Player player, FbsmpDatabase.SavedLocation saved) {
        World world = Bukkit.getWorld(saved.world());
        if (world == null) {
            player.sendMessage(color("&cThat location's world is not loaded."));
            return;
        }
        Location location = new Location(world, saved.x(), saved.y(), saved.z(), saved.yaw(), saved.pitch());
        if (!safeStandingLocation(location)) {
            player.sendMessage(color("&cThat location is no longer safe."));
            return;
        }
        player.teleport(location);
    }

    private static Optional<Location> safeRandomLocation(World world, int minRadius, int maxRadius) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location spawn = world.getSpawnLocation();
        for (int attempt = 0; attempt < 40; attempt++) {
            double angle = random.nextDouble(0, Math.PI * 2);
            int radius = random.nextInt(minRadius, maxRadius);
            int x = spawn.getBlockX() + (int) (Math.cos(angle) * radius);
            int z = spawn.getBlockZ() + (int) (Math.sin(angle) * radius);
            var border = world.getWorldBorder();
            if (x < border.getCenter().getBlockX() - border.getSize() / 2
                    || x > border.getCenter().getBlockX() + border.getSize() / 2
                    || z < border.getCenter().getBlockZ() - border.getSize() / 2
                    || z > border.getCenter().getBlockZ() + border.getSize() / 2) {
                continue;
            }
            Block ground = world.getHighestBlockAt(x, z);
            Location target = new Location(world, x + 0.5, ground.getY() + 1, z + 0.5);
            if (safeStandingLocation(target)) {
                return Optional.of(target);
            }
        }
        return Optional.empty();
    }

    private static boolean safeStandingLocation(Location location) {
        if (location.getWorld() == null) {
            return false;
        }
        Block floor = location.clone().subtract(0, 1, 0).getBlock();
        Material floorType = floor.getType();
        return floorType.isSolid() && floorType != Material.LAVA && floorType != Material.MAGMA_BLOCK
                && floorType != Material.CAMPFIRE && floorType != Material.SOUL_CAMPFIRE
                && floorType != Material.CACTUS && floorType != Material.POWDER_SNOW
                && location.getBlock().isPassable() && location.clone().add(0, 1, 0).getBlock().isPassable();
    }

    private static boolean validName(String name, int maxLength) {
        return name != null && name.length() <= maxLength && name.matches("[A-Za-z0-9_-]{1," + maxLength + "}");
    }

    private static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private <T> void query(CommandSender sender, DatabaseWork<T> task, BiConsumer<CommandSender, T> success) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                T value = task.run();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!(sender instanceof Player player) || player.isOnline()) {
                        success.accept(sender, value);
                    }
                });
            } catch (Exception exception) {
                plugin.getLogger().warning("Database command failed: " + exception.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!(sender instanceof Player player) || player.isOnline()) {
                        sender.sendMessage(color("&cThat request could not be completed. Please try again."));
                    }
                });
            }
        });
    }

    @FunctionalInterface
    private interface DatabaseWork<T> {
        T run() throws Exception;
    }

    private record PendingGift(UUID recipient, String recipientName, long amountMinor,
                               long expiresAt, boolean accepted) {}
    private record TeleportRequest(UUID requester, UUID recipient, boolean here, long expiresAt) {}
    private record StatsDisplay(FbsmpDatabase.PlayerStats stats, String date) {}

}