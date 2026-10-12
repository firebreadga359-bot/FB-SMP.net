package net.firebreadmc.fbsmp.paper.admin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.firebreadmc.fbsmp.common.Money;
import net.firebreadmc.fbsmp.paper.FbsmpPlugin;
import net.firebreadmc.fbsmp.paper.data.FbsmpDatabase;
import net.firebreadmc.fbsmp.paper.menus.ShopMenu;
import net.firebreadmc.fbsmp.paper.shop.ShopPriceAdmin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The FB-SMP admin control panel: an HTTP API plus bundled single-page UI.
 *
 * <p>Security model: loopback-only bind, Host allow-list (blocks DNS rebinding),
 * HttpOnly SameSite=Strict session cookie, per-session CSRF token, login rate
 * limiting, PBKDF2 password hashing and server-side role checks on every
 * sensitive action. The legacy shared token still authenticates as OWNER so an
 * upgrade never locks the owner out. When deployed behind a reverse proxy the
 * proxy must terminate TLS and rewrite the Host header to a loopback value.
 */
public final class PrivateAdminPanel implements AutoCloseable {
    private static final long SESSION_LIFETIME_MILLIS = 30 * 60 * 1000L;
    private static final Gson GSON = new Gson();
    private static final int MAX_CONSOLE_LINES = 200;

    private final FbsmpPlugin plugin;
    private final FbsmpDatabase database;
    private final ShopMenu shopMenu;
    private final ShopPriceAdmin priceAdmin;
    private final ConsoleBuffer console = new ConsoleBuffer();
    private final String legacyToken;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, LoginWindow> loginWindows = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "fbsmp-admin-panel");
        thread.setDaemon(true);
        return thread;
    });
    private HttpServer server;
    private final ThreadLocal<byte[]> requestBody = new ThreadLocal<>();

    public PrivateAdminPanel(FbsmpPlugin plugin, FbsmpDatabase database, ShopMenu shopMenu) throws IOException {
        this.plugin = plugin;
        this.database = database;
        this.shopMenu = shopMenu;
        this.priceAdmin = new ShopPriceAdmin(plugin.getDataFolder(), shopMenu);
        this.legacyToken = loadOrCreateToken(plugin.getDataFolder().toPath().resolve("admin-token"));
    }

    public void start() throws IOException {
        String bindHost = plugin.getConfig().getString("admin-panel.bind-address", "127.0.0.1");
        InetAddress bindAddress = InetAddress.getByName(bindHost);
        if (!bindAddress.isLoopbackAddress()
                && !plugin.getConfig().getBoolean("admin-panel.allow-external-bind", false)) {
            throw new IllegalArgumentException(
                    "Admin panel bind address must be loopback-only unless admin-panel.allow-external-bind is true.");
        }
        int port = plugin.getConfig().getInt("admin-panel.port", 8765);
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Admin panel port must be between 1024 and 65535.");
        }
        server = HttpServer.create(new InetSocketAddress(bindAddress, port), 32);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
        console.install();
        plugin.getLogger().info("Admin control panel listening on " + bindAddress.getHostAddress() + ":" + port
                + ". Sign in with the token in the plugin data folder's admin-token file.");
    }

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        requestBody.remove();
        try {
            if (!validHost(exchange)) {
                drain(exchange);
                respond(exchange, 403, Map.of("error", "Invalid host header."));
                return;
            }
            if (!isLocalRequest(exchange) && !isProxiedRequest(exchange)) {
                drain(exchange);
                respond(exchange, 403, Map.of("error", "Local access only."));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (path.equals("/") && method.equals("GET")) {
                sendResource(exchange, "panel/index.html", "text/html; charset=utf-8");
                return;
            }
            if (path.equals("/health") && method.equals("GET")) {
                respond(exchange, 200, Map.of("status", "ok", "service", "fbsmp-admin-panel"));
                return;
            }
            if (path.equals("/api/login") && method.equals("POST")) {
                login(exchange);
                return;
            }
            Session session = authenticatedSession(exchange);
            if (session == null) {
                drain(exchange);
                respond(exchange, 401, Map.of("error", "Sign in to continue."));
                return;
            }
            if (path.equals("/api/session") && method.equals("GET")) {
                respond(exchange, 200, Map.of("csrfToken", session.csrfToken, "username", session.username,
                        "role", session.role.name(), "permissions", session.role.permissions()));
                return;
            }
            if (path.equals("/api/logout") && method.equals("POST")) {
                requireCsrf(exchange, session);
                String sessionId = cookie(exchange, "FBSMP_SESSION");
                if (sessionId != null) {
                    sessions.remove(sessionId);
                }
                exchange.getResponseHeaders().add("Set-Cookie",
                        "FBSMP_SESSION=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");
                respond(exchange, 200, Map.of("message", "Signed out."));
                return;
            }

            if (!method.equals("GET")) {
                requireCsrf(exchange, session);
            }

            // Read-only endpoints.
            if (path.equals("/api/status") && method.equals("GET")) {
                require(session, "status.view");
                status(exchange);
                return;
            }
            if (path.equals("/api/overview") && method.equals("GET")) {
                require(session, "status.view");
                overview(exchange);
                return;
            }
            if (path.equals("/api/console") && method.equals("GET")) {
                require(session, "logs.view");
                respond(exchange, 200, Map.of("lines", console.recent(MAX_CONSOLE_LINES)));
                return;
            }
            if (path.equals("/api/players") && method.equals("GET")) {
                require(session, "players.view");
                players(exchange);
                return;
            }
            if (path.equals("/api/player") && method.equals("GET")) {
                require(session, "players.view");
                playerDetail(exchange);
                return;
            }
            if (path.equals("/api/transactions") && method.equals("GET")) {
                require(session, "economy.view");
                respondDatabase(exchange, database::recentTransactions);
                return;
            }
            if (path.equals("/api/economy") && method.equals("GET")) {
                require(session, "economy.view");
                economy(exchange);
                return;
            }
            if (path.equals("/api/loans") && method.equals("GET")) {
                require(session, "economy.view");
                respondDatabase(exchange, database::adminLoans);
                return;
            }
            if (path.equals("/api/mortgages") && method.equals("GET")) {
                require(session, "economy.view");
                respondDatabase(exchange, database::adminMortgages);
                return;
            }
            if (path.equals("/api/auctions") && method.equals("GET")) {
                require(session, "economy.view");
                respondDatabase(exchange, database::adminAuctions);
                return;
            }
            if (path.equals("/api/orders") && method.equals("GET")) {
                require(session, "economy.view");
                respondDatabase(exchange, database::adminOrders);
                return;
            }
            if (path.equals("/api/moderation") && method.equals("GET")) {
                require(session, "players.view");
                respondDatabase(exchange, database::recentModeration);
                return;
            }
            if (path.equals("/api/logs") && method.equals("GET")) {
                require(session, "logs.view");
                respondDatabase(exchange, database::recentAuditEvents);
                return;
            }
            if (path.equals("/api/audit") && method.equals("GET")) {
                require(session, "logs.view");
                respondDatabase(exchange, database::recentAuditLog);
                return;
            }
            if (path.equals("/api/backups") && method.equals("GET")) {
                require(session, "backups.manage");
                backups(exchange);
                return;
            }
            if (path.equals("/api/config") && method.equals("GET")) {
                require(session, "settings.manage");
                configuration(exchange);
                return;
            }
            if (path.equals("/api/plugins") && method.equals("GET")) {
                require(session, "plugins.manage");
                plugins(exchange);
                return;
            }
            if (path.equals("/api/worlds") && method.equals("GET")) {
                require(session, "worlds.manage");
                worlds(exchange);
                return;
            }
            if (path.equals("/api/prices") && method.equals("GET")) {
                require(session, "shop.view");
                prices(exchange);
                return;
            }
            if (path.equals("/api/announcements") && method.equals("GET")) {
                require(session, "announce.manage");
                try {
                    respond(exchange, 200, database.pendingAnnouncements());
                } catch (SQLException exception) {
                    respond(exchange, 503, Map.of("error", "Database unavailable."));
                }
                return;
            }
            if (path.equals("/api/tasks") && method.equals("GET")) {
                require(session, "schedule.manage");
                try {
                    respond(exchange, 200, database.scheduledTasks());
                } catch (SQLException exception) {
                    respond(exchange, 503, Map.of("error", "Database unavailable."));
                }
                return;
            }
            if (path.equals("/api/admins") && method.equals("GET")) {
                require(session, "admins.manage");
                admins(exchange);
                return;
            }

            // Mutating endpoints.
            if (path.equals("/api/backup") && method.equals("POST")) {
                require(session, "backups.manage");
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        plugin.backupDatabase();
                        audit(session, "BACKUP_CREATE", "", "", "");
                    } catch (Exception exception) {
                        plugin.getLogger().warning("Admin panel backup failed: " + exception.getMessage());
                    }
                });
                respond(exchange, 202, Map.of("message", "Backup queued."));
                return;
            }
            if (path.equals("/api/console") && method.equals("POST")) {
                require(session, "console.send");
                dispatchConsole(exchange, session);
                return;
            }
            if (path.equals("/api/announce") && method.equals("POST")) {
                require(session, "announce.manage");
                announce(exchange, session);
                return;
            }
            if (path.equals("/api/server") && method.equals("POST")) {
                require(session, "server.control");
                serverControl(exchange, session);
                return;
            }
            if (path.equals("/api/economy") && method.equals("POST")) {
                require(session, "economy.manage");
                economyAction(exchange, session);
                return;
            }
            if (path.equals("/api/player") && method.equals("POST")) {
                require(session, "players.manage");
                playerAction(exchange, session);
                return;
            }
            if (path.equals("/api/price") && method.equals("POST")) {
                require(session, "shop.manage");
                priceAction(exchange, session);
                return;
            }
            if (path.equals("/api/world") && method.equals("POST")) {
                require(session, "worlds.manage");
                worldAction(exchange, session);
                return;
            }
            if (path.equals("/api/settings") && method.equals("POST")) {
                require(session, "settings.manage");
                settingsAction(exchange, session);
                return;
            }
            if (path.equals("/api/tasks") && method.equals("POST")) {
                require(session, "schedule.manage");
                taskAction(exchange, session);
                return;
            }
            if (path.equals("/api/admins") && method.equals("POST")) {
                require(session, "admins.manage");
                adminAction(exchange, session);
                return;
            }
            respond(exchange, 404, Map.of("error", "Not found."));
        } catch (HttpError error) {
            drain(exchange);
            respond(exchange, error.status, Map.of("error", error.getMessage()));
        } catch (Exception exception) {
            plugin.getLogger().warning("Admin panel request failed: " + exception.getMessage());
            try {
                drain(exchange);
                respond(exchange, 500, Map.of("error", "The request could not be completed."));
            } catch (IOException ignored) {
                // The client may already have disconnected.
            }
        } finally {
            requestBody.remove();
            exchange.close();
        }
    }

    // ------------------------------------------------------------------
    // Authentication
    // ------------------------------------------------------------------

    private void login(HttpExchange exchange) throws IOException {
        String client = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (!allowLogin(client)) {
            respond(exchange, 429, Map.of("error", "Too many login attempts. Wait one minute and try again."));
            return;
        }
        JsonObject input = readJson(exchange);
        String username = input.has("username") ? input.get("username").getAsString().trim() : "";
        String password = input.has("password") ? input.get("password").getAsString() : "";
        String suppliedToken = input.has("token") ? input.get("token").getAsString() : "";

        AdminRole role;
        String name;
        if (!suppliedToken.isEmpty() && constantTimeEquals(legacyToken, suppliedToken)) {
            role = AdminRole.OWNER;
            name = "owner (token)";
        } else if (!username.isEmpty() && !password.isEmpty()) {
            Optional<FbsmpDatabase.AdminUserRow> user = queryUser(username);
            if (user.isEmpty() || !PasswordHasher.verify(password, user.get().passwordSalt(),
                    user.get().iterations(), user.get().passwordHash())) {
                respond(exchange, 401, Map.of("error", "The username or password is not valid."));
                return;
            }
            role = AdminRole.parse(user.get().role(), AdminRole.VIEWER);
            name = user.get().username();
            try {
                database.markAdminLogin(user.get().id(), System.currentTimeMillis());
            } catch (SQLException ignored) {
                // Login still succeeds even if the timestamp cannot be stored.
            }
        } else {
            respond(exchange, 401, Map.of("error", "Provide the owner token, or a username and password."));
            return;
        }

        byte[] sessionBytes = randomBytes(32);
        byte[] csrfBytes = randomBytes(24);
        String sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(sessionBytes);
        String csrfToken = Base64.getUrlEncoder().withoutPadding().encodeToString(csrfBytes);
        sessions.put(sessionId, new Session(csrfToken, System.currentTimeMillis() + SESSION_LIFETIME_MILLIS, name, role));
        exchange.getResponseHeaders().add("Set-Cookie",
                "FBSMP_SESSION=" + sessionId + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=1800"
                        + secureFlag(exchange));
        respond(exchange, 200, Map.of("csrfToken", csrfToken, "username", name, "role", role.name(),
                "permissions", role.permissions()));
    }

    private Optional<FbsmpDatabase.AdminUserRow> queryUser(String username) {
        try {
            return database.adminUser(username);
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    private Session authenticatedSession(HttpExchange exchange) {
        String sessionId = cookie(exchange, "FBSMP_SESSION");
        if (sessionId == null) {
            return null;
        }
        Session session = sessions.get(sessionId);
        if (session != null && session.expiresAt < System.currentTimeMillis()) {
            sessions.remove(sessionId);
            return null;
        }
        return session;
    }

    private static void require(Session session, String permission) {
        if (!session.role.allows(permission)) {
            throw new HttpError(403, "Your role does not permit this action.");
        }
    }

    private void requireCsrf(HttpExchange exchange, Session session) {
        String supplied = exchange.getRequestHeaders().getFirst("X-CSRF-Token");
        if (supplied == null || !constantTimeEquals(session.csrfToken, supplied)) {
            throw new HttpError(403, "CSRF validation failed.");
        }
    }

    private boolean allowLogin(String key) {
        long now = System.currentTimeMillis();
        LoginWindow window = loginWindows.compute(key, (ignored, current) ->
                current == null || now - current.startedAt >= 60_000L
                        ? new LoginWindow(now, 1)
                        : new LoginWindow(current.startedAt, current.attempts + 1));
        return window.attempts <= 8;
    }

    // ------------------------------------------------------------------
    // Status, overview and monitoring
    // ------------------------------------------------------------------

    private void status(HttpExchange exchange) throws IOException, SQLException {
        FbsmpDatabase.AdminSnapshot snapshot = database.adminSnapshot();
        respond(exchange, 200, Map.ofEntries(
                Map.entry("server", "FB-SMP"),
                Map.entry("version", plugin.getDescription().getVersion()),
                Map.entry("paper", Bukkit.getVersion()),
                Map.entry("minecraft", Bukkit.getMinecraftVersion()),
                Map.entry("status", "online"),
                Map.entry("uptimeSeconds", plugin.uptimeSeconds()),
                Map.entry("startedAt", ServerMetrics.iso(System.currentTimeMillis() - plugin.uptimeSeconds() * 1000L)),
                Map.entry("playerRecords", snapshot.playerCount()),
                Map.entry("totalBalance", Money.format(snapshot.totalBalanceMinor())),
                Map.entry("totalBalanceMinor", snapshot.totalBalanceMinor()),
                Map.entry("transactionCount", snapshot.transactionCount()),
                Map.entry("onlinePlayers", ServerMetrics.onlinePlayers()),
                Map.entry("maximumPlayers", ServerMetrics.maxPlayers()),
                Map.entry("tps", ServerMetrics.tps()),
                Map.entry("mspt", ServerMetrics.mspt()),
                Map.entry("heapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()),
                Map.entry("heapMaxBytes", Runtime.getRuntime().maxMemory()),
                Map.entry("diskTotalBytes", ServerMetrics.diskTotalBytes()),
                Map.entry("diskUsableBytes", ServerMetrics.diskUsableBytes()),
                Map.entry("systemLoadPercent", ServerMetrics.systemLoadPercent()),
                Map.entry("processors", Runtime.getRuntime().availableProcessors()),
                Map.entry("maintenance", maintenanceMode()),
                Map.entry("serverTime", Instant.now().toString())
        ));
    }

    private void overview(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> online = new ArrayList<>();
        for (Player player : ServerMetrics.onlinePlayerList()) {
            online.add(Map.of("name", player.getName(), "uuid", player.getUniqueId().toString(),
                    "edition", player.getUniqueId().version() == 4 ? "Java" : "Bedrock",
                    "world", player.getWorld().getName()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("online", online);
        result.put("worlds", ServerMetrics.worlds());
        result.put("recentCommands", safe(() -> database.recentConsoleCommands(20)));
        result.put("recentAudit", safe(() -> database.recentAuditLog(20)));
        respond(exchange, 200, result);
    }

    private void plugins(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> installed = new ArrayList<>();
        for (org.bukkit.plugin.Plugin installedPlugin : Bukkit.getPluginManager().getPlugins()) {
            installed.add(Map.of(
                    "name", installedPlugin.getName(),
                    "version", installedPlugin.getDescription().getVersion(),
                    "enabled", installedPlugin.isEnabled(),
                    "authors", String.join(", ", installedPlugin.getDescription().getAuthors())));
        }
        respond(exchange, 200, installed);
    }

    private void worlds(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ServerMetrics.WorldInfo info : ServerMetrics.worlds()) {
            World world = Bukkit.getWorld(info.name());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", info.name());
            row.put("environment", info.environment());
            row.put("sizeBytes", info.sizeBytes());
            row.put("players", info.players());
            row.put("difficulty", world == null ? "" : world.getDifficulty().name());
            row.put("seed", world == null ? "" : String.valueOf(world.getSeed()));
            row.put("time", world == null ? 0L : world.getTime());
            row.put("autoSave", world == null || world.isAutoSave());
            row.put("pvp", world != null && world.getPVP());
            result.add(row);
        }
        respond(exchange, 200, result);
    }

    // ------------------------------------------------------------------
    // Players
    // ------------------------------------------------------------------

    private void players(HttpExchange exchange) throws IOException {
        String search = query(exchange, "q");
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (FbsmpDatabase.PlayerRow row : database.playerDirectory(search, 200)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("uuid", row.uuid());
                item.put("name", row.name());
                item.put("balanceMinor", row.balanceMinor());
                item.put("formattedBalance", Money.format(row.balanceMinor()));
                item.put("edition", UUID.fromString(row.uuid()).version() == 4 ? "Java" : "Bedrock");
                item.put("online", Bukkit.getPlayer(UUID.fromString(row.uuid())) != null);
                item.put("firstJoinAt", row.firstJoinAt());
                item.put("lastSeenAt", row.lastSeenAt());
                item.put("playtimeSeconds", row.playtimeSeconds());
                rows.add(item);
            }
            respond(exchange, 200, rows);
        } catch (SQLException exception) {
            respond(exchange, 503, Map.of("error", "Database unavailable."));
        }
    }

    private void playerDetail(HttpExchange exchange) throws IOException {
        String name = query(exchange, "name");
        try {
            Optional<FbsmpDatabase.PlayerRow> found = name == null || name.isBlank()
                    ? Optional.empty() : database.playerByName(name);
            if (found.isEmpty()) {
                respond(exchange, 404, Map.of("error", "Player not found."));
                return;
            }
            FbsmpDatabase.PlayerRow row = found.get();
            UUID id = UUID.fromString(row.uuid());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("uuid", row.uuid());
            detail.put("name", row.name());
            detail.put("balanceMinor", row.balanceMinor());
            detail.put("formattedBalance", Money.format(row.balanceMinor()));
            detail.put("edition", id.version() == 4 ? "Java" : "Bedrock");
            detail.put("firstJoinAt", row.firstJoinAt());
            detail.put("lastSeenAt", row.lastSeenAt());
            detail.put("playtimeSeconds", row.playtimeSeconds());
            detail.put("transactions", database.transactionsFor(id, 50));
            detail.put("moderation", database.moderationFor(id, 50));
            Player online = Bukkit.getPlayer(id);
            detail.put("online", online != null);
            if (online != null) {
                detail.put("world", online.getWorld().getName());
                detail.put("location", Map.of("x", online.getLocation().getX(), "y", online.getLocation().getY(),
                        "z", online.getLocation().getZ()));
                detail.put("gameMode", online.getGameMode().name());
                detail.put("inventory", inventorySnapshot(online));
            }
            respond(exchange, 200, detail);
        } catch (SQLException exception) {
            respond(exchange, 503, Map.of("error", "Database unavailable."));
        }
    }

    private static List<Map<String, Object>> inventorySnapshot(Player player) {
        List<Map<String, Object>> items = new ArrayList<>();
        var contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            var stack = contents[slot];
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            items.add(Map.of("slot", slot, "item", stack.getType().name(), "amount", stack.getAmount()));
        }
        return items;
    }

    // ------------------------------------------------------------------
    // Economy
    // ------------------------------------------------------------------

    private void economy(HttpExchange exchange) throws IOException {
        try {
            FbsmpDatabase.EconomyReport report = database.economyReport();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("inCirculation", Money.format(report.inCirculationMinor()));
            result.put("inCirculationMinor", report.inCirculationMinor());
            result.put("earned", Money.format(report.earnedMinor()));
            result.put("spent", Money.format(report.spentMinor()));
            result.put("shopPurchases", report.shopPurchases());
            result.put("shopSales", report.shopSales());
            result.put("auctionVolume", Money.format(report.auctionVolumeMinor()));
            result.put("richest", database.topBalances(query(exchange, "q"), 25));
            result.put("transactions", database.recentTransactions(50));
            respond(exchange, 200, result);
        } catch (SQLException exception) {
            respond(exchange, 503, Map.of("error", "Database unavailable."));
        }
    }

    private void economyAction(HttpExchange exchange, Session session) throws IOException {
        JsonObject body = readJson(exchange);
        String playerName = required(body, "player");
        String action = required(body, "action").toUpperCase(Locale.ROOT);
        String reason = optional(body, "reason");
        try {
            Optional<UUID> id = resolvePlayer(playerName);
            if (id.isEmpty()) {
                respond(exchange, 404, Map.of("error", "Player not found."));
                return;
            }
            UUID target = id.get();
            long before = database.balance(target);
            long resultBalance;
            switch (action) {
                case "SET" -> {
                    long targetMinor = Money.parseMinorUnits(required(body, "amount"));
                    if (targetMinor < 0) {
                        respond(exchange, 400, Map.of("error", "Balance cannot be negative."));
                        return;
                    }
                    long delta = targetMinor - before;
                    if (delta == 0) {
                        respond(exchange, 200, Map.of("message", "Balance unchanged."));
                        return;
                    }
                    if (delta > 0) {
                        database.credit(target, delta, "ADMIN_ADJUST", "Set by " + session.username, System.currentTimeMillis());
                    } else if (!database.debit(target, -delta, "ADMIN_ADJUST", "Set by " + session.username, System.currentTimeMillis())) {
                        respond(exchange, 409, Map.of("error", "Insufficient funds for that adjustment."));
                        return;
                    }
                    resultBalance = targetMinor;
                }
                case "GIVE" -> {
                    long amountMinor = Money.parseMinorUnits(required(body, "amount"));
                    if (!database.credit(target, amountMinor, "ADMIN_GRANT", "Granted by " + session.username, System.currentTimeMillis())) {
                        respond(exchange, 409, Map.of("error", "Grant failed."));
                        return;
                    }
                    resultBalance = before + amountMinor;
                }
                case "TAKE" -> {
                    long amountMinor = Money.parseMinorUnits(required(body, "amount"));
                    if (!database.debit(target, amountMinor, "ADMIN_DEBIT", "Removed by " + session.username, System.currentTimeMillis())) {
                        respond(exchange, 409, Map.of("error", "Player does not have that much."));
                        return;
                    }
                    resultBalance = before - amountMinor;
                }
                default -> {
                    respond(exchange, 400, Map.of("error", "Unknown economy action."));
                    return;
                }
            }
            audit(session, "ECONOMY_" + action, playerName,
                    Money.format(before), Money.format(resultBalance));
            respond(exchange, 200, Map.of("message", "Balance updated.", "balance", Money.format(resultBalance)));
        } catch (IllegalArgumentException exception) {
            respond(exchange, 400, Map.of("error", exception.getMessage()));
        } catch (SQLException exception) {
            respond(exchange, 503, Map.of("error", "Database unavailable."));
        }
    }

    // ------------------------------------------------------------------
    // Player management actions
    // ------------------------------------------------------------------

    private void playerAction(HttpExchange exchange, Session session) throws IOException {
        JsonObject body = readJson(exchange);
        String playerName = required(body, "player");
        String action = required(body, "action").toUpperCase(Locale.ROOT);
        String reason = optional(body, "reason");
        try {
            UUID target = resolvePlayer(playerName).orElse(null);
            if (target == null) {
                respond(exchange, 404, Map.of("error", "Player not found."));
                return;
            }
            Player online = Bukkit.getPlayer(target);
            switch (action) {
                case "KICK" -> {
                    if (online == null) {
                        respond(exchange, 409, Map.of("error", "Player is not online."));
                        return;
                    }
                    online.kickPlayer("Kicked by an administrator." + (reason.isBlank() ? "" : " Reason: " + reason));
                }
                case "BAN" -> {
                    database.recordModeration(target, actorUuid(session), "BAN",
                            reason.isBlank() ? "No reason specified." : reason, null, System.currentTimeMillis());
                    if (online != null) {
                        online.kickPlayer("You are banned from FB-SMP." + (reason.isBlank() ? "" : " Reason: " + reason));
                    }
                }
                case "MUTE" -> database.recordModeration(target, actorUuid(session), "TIMEOUT",
                        reason.isBlank() ? "No reason specified." : reason,
                        System.currentTimeMillis() + 3_600_000L, System.currentTimeMillis());
                case "UNBAN" -> database.clearActiveModeration(target, "BAN", System.currentTimeMillis());
                case "UNMUTE" -> database.clearActiveModeration(target, "TIMEOUT", System.currentTimeMillis());
                case "GAMEMODE" -> {
                    if (online == null) {
                        respond(exchange, 409, Map.of("error", "Player is not online."));
                        return;
                    }
                    String mode = required(body, "value").toUpperCase(Locale.ROOT);
                    try {
                        online.setGameMode(org.bukkit.GameMode.valueOf(mode));
                    } catch (IllegalArgumentException exception) {
                        respond(exchange, 400, Map.of("error", "Unknown game mode."));
                        return;
                    }
                }
                case "TELEPORT" -> {
                    if (online == null) {
                        respond(exchange, 409, Map.of("error", "Player is not online."));
                        return;
                    }
                    String worldName = required(body, "world");
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        respond(exchange, 400, Map.of("error", "Unknown world."));
                        return;
                    }
                    double x = parseDouble(body, "x");
                    double y = parseDouble(body, "y");
                    double z = parseDouble(body, "z");
                    online.teleport(new org.bukkit.Location(world, x, y, z));
                }
                case "RESET" -> {
                    if (!"RESET".equalsIgnoreCase(optional(body, "confirm"))) {
                        respond(exchange, 400, Map.of("error", "Type RESET in the confirm field to wipe a player."));
                        return;
                    }
                    database.wipePlayer(target);
                }
                default -> {
                    respond(exchange, 400, Map.of("error", "Unknown player action."));
                    return;
                }
            }
            audit(session, "PLAYER_" + action, playerName, "", reason);
            respond(exchange, 200, Map.of("message", "Action applied."));
        } catch (SQLException exception) {
            respond(exchange, 503, Map.of("error", "Database unavailable."));
        }
    }

    // ------------------------------------------------------------------
    // Server control and console
    // ------------------------------------------------------------------

    private void dispatchConsole(HttpExchange exchange, Session session) throws IOException {
        JsonObject body = readJson(exchange);
        String command = required(body, "command").trim();
        if (command.isEmpty()) {
            respond(exchange, 400, Map.of("error", "Command is empty."));
            return;
        }
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        final String dispatched = command;
        try {
            database.logConsoleCommand(session.username, dispatched, System.currentTimeMillis());
        } catch (SQLException ignored) {
            // Command still runs; the audit trail is best-effort.
        }
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), dispatched));
        audit(session, "CONSOLE_COMMAND", dispatched, "", "");
        respond(exchange, 200, Map.of("message", "Command dispatched."));
    }

    private void announce(HttpExchange exchange, Session session) throws IOException {
        JsonObject body = readJson(exchange);
        String message = required(body, "message").trim();
        if (message.isEmpty() || message.length() > 512) {
            respond(exchange, 400, Map.of("error", "Message must be 1-512 characters."));
            return;
        }
        try {
            database.createAnnouncement(message, session.username, System.currentTimeMillis());
        } catch (SQLException exception) {
            respond(exchange, 503, Map.of("error", "Database unavailable."));
            return;
        }
        audit(session, "ANNOUNCE", "", "", message);
        respond(exchange, 200, Map.of("message", "Announcement queued."));
    }

    private void serverControl(HttpExchange exchange, Session session) throws IOException {
        JsonObject body = readJson(exchange);
        String action = required(body, "action").toUpperCase(Locale.ROOT);
        switch (action) {
            case "BROADCAST" -> {
                String message = required(body, "message");
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcastMessage(
                        org.bukkit.ChatColor.GOLD + "[FB-SMP] " + org.bukkit.ChatColor.WHITE + message));
            }
            case "SAVE" -> Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.getWorlds().forEach(World::save));
            case "RELOAD" -> Bukkit.getScheduler().runTask(plugin, () -> Bukkit.reload());
            case "STOP" -> {
                // Graceful: warn, save every world, then shut down. The supervisor
                // restarts the stack afterwards.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Bukkit.broadcastMessage(org.bukkit.ChatColor.RED + "Server is restarting now. Please rejoin shortly.");
                    Bukkit.getWorlds().forEach(World::save);
                    Bukkit.shutdown();
                });
            }
            default -> {
                respond(exchange, 400, Map.of("error", "Unknown server action."));
                return;
            }
        }
        audit(session, "SERVER_" + action, "", "", optional(body, "message"));
        respond(exchange, 200, Map.of("message", "Server action dispatched."));
    }

    // ------------------------------------------------------------------
    // Prices and shop
    // ------------------------------------------------------------------

    private void prices(HttpExchange exchange) throws IOException {
        String search = query(exchange, "q");
        String itemId = query(exchange, "item");
        if (itemId != null && !itemId.isBlank()) {
            respond(exchange, 200, priceAdmin.price(itemId));
            return;
        }
        respond(exchange, 200, priceAdmin.list(search, 300));
    }

    private void priceAction(HttpExchange exchange, Session session) throws IOException {
        JsonObject body = readJson(exchange);
        String itemId = required(body, "item");
        String action = required(body, "action").toUpperCase(Locale.ROOT);
        try {
            switch (action) {
                case "SET_BUY" -> priceAdmin.setBuy(itemId, Money.parseMinorUnits(required(body, "value")));
                case "SET_SELL" -> priceAdmin.setSell(itemId, Money.parseMinorUnits(required(body, "value")));
                case "DISABLE" -> priceAdmin.setDisabled(itemId, true);
                case "ENABLE" -> priceAdmin.setDisabled(itemId, false);
                case "UNDO" -> {
                    if (!priceAdmin.undoLast()) {
                        respond(exchange, 409, Map.of("error", "There is no recent price change to undo."));
                        return;
                    }
                }
                default -> {
                    respond(exchange, 400, Map.of("error", "Unknown price action."));
                    return;
                }
            }
            audit(session, "PRICE_" + action, itemId, "", optional(body, "value"));
            respond(exchange, 200, Map.of("message", "Price change applied and the live shop reloaded."));
        } catch (IllegalArgumentException exception) {
            respond(exchange, 400, Map.of("error", exception.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // Worlds and settings
    // ------------------------------------------------------------------

    private void worldAction(HttpExchange exchange, Session session) throws IOException {
        JsonObject body = readJson(exchange);
        String worldName = required(body, "world");
        String action = required(body, "action").toUpperCase(Locale.ROOT);
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            respond(exchange, 400, Map.of("error", "Unknown world."));
            return;
        }
        switch (action) {
            case "SAVE" -> Bukkit.getScheduler().runTask(plugin, (Runnable) world::save);
            case "DIFFICULTY" -> Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    world.setDifficulty(org.bukkit.Difficulty.valueOf(required(body, "value").toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // Invalid difficulty is ignored.
                }
            });
            case "PVP" -> Bukkit.getScheduler().runTask(plugin, () -> world.setPVP(Boolean.parseBoolean(required(body, "value"))));
            case "TIME" -> Bukkit.getScheduler().runTask(plugin, () -> world.setTime(Long.parseLong(required(body, "value"))));
            case "WEATHER" -> Bukkit.getScheduler().runTask(plugin, () -> {
                String value = required(body, "value").toUpperCase(Locale.ROOT);
                if (value.equals("STORM")) {
                    world.setStorm(true);
                } else if (value.equals("THUNDER")) {
                    world.setStorm(true);
                    world.setThundering(true);
                } else {
                    world.setStorm(false);
                    world.setThundering(false);
                }
            });
            default -> {
                respond(exchange, 400, Map.of("error", "Unknown world action."));
                return;
            }
        }
        audit(session, "WORLD_" + action, worldName, "", optional(body, "value"));
        respond(exchange, 200, Map.of("message", "World action dispatched."));
    }

    private void settingsAction(HttpExchange exchange, Session session) throws IOException {
        JsonObject body = readJson(exchange);
        String key = required(body, "key");
        String value = required(body, "value");
        Set<String> allowed = Set.of("timezone", "maintenance", "maintenance-message");
        if (!allowed.contains(key)) {
            respond(exchange, 400, Map.of("error", "Unknown setting."));
            return;
        }
        try {
            database.setGlobalSetting(key, value, System.currentTimeMillis());
        } catch (SQLException exception) {
            respond(exchange, 503, Map.of("error", "Database unavailable."));
            return;
        }
        audit(session, "SETTING_SET", key, "", value);
        respond(exchange, 200, Map.of("message", "Setting saved."));
    }

    // ------------------------------------------------------------------
    // Scheduled tasks
    // ------------------------------------------------------------------

    private void taskAction(HttpExchange exchange, Session session) throws IOException {
        JsonObject body = readJson(exchange);
        String action = required(body, "action").toUpperCase(Locale.ROOT);
        try {
            switch (action) {
                case "CREATE" -> {
                    String kind = required(body, "kind").toUpperCase(Locale.ROOT);
                    if (!kind.equals("ANNOUNCE") && !kind.equals("COMMAND")) {
                        respond(exchange, 400, Map.of("error", "Task kind must be ANNOUNCE or COMMAND."));
                        return;
                    }
                    String payload = required(body, "payload");
                    int interval = Integer.parseInt(required(body, "intervalMinutes"));
                    long next = System.currentTimeMillis() + Math.max(1, interval) * 60_000L;
                    database.createScheduledTask(kind, payload, interval, next, System.currentTimeMillis());
                }
                case "TOGGLE" -> database.setScheduledTaskEnabled(
                        Long.parseLong(required(body, "id")), Boolean.parseBoolean(required(body, "value")));
                case "DELETE" -> database.deleteScheduledTask(Long.parseLong(required(body, "id")));
                default -> {
                    respond(exchange, 400, Map.of("error", "Unknown task action."));
                    return;
                }
            }
            audit(session, "TASK_" + action, "", "", optional(body, "payload"));
            respond(exchange, 200, Map.of("message", "Scheduled task updated."));
        } catch (NumberFormatException exception) {
            respond(exchange, 400, Map.of("error", "A numeric field was not a number."));
        } catch (SQLException exception) {
            respond(exchange, 503, Map.of("error", "Database unavailable."));
        }
    }

    // ------------------------------------------------------------------
    // Admin accounts
    // ------------------------------------------------------------------

    private void admins(HttpExchange exchange) throws IOException {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (FbsmpDatabase.AdminUserRow user : database.adminUsers()) {
                rows.add(Map.of("id", user.id(), "username", user.username(), "role", user.role(),
                        "createdAt", user.createdAt(), "lastLoginAt", user.lastLoginAt() == null ? 0L : user.lastLoginAt()));
            }
            respond(exchange, 200, rows);
        } catch (SQLException exception) {
            respond(exchange, 503, Map.of("error", "Database unavailable."));
        }
    }

    private void adminAction(HttpExchange exchange, Session session) throws IOException {
        JsonObject body = readJson(exchange);
        String action = required(body, "action").toUpperCase(Locale.ROOT);
        try {
            switch (action) {
                case "CREATE" -> {
                    String username = required(body, "username").trim();
                    String password = required(body, "password");
                    if (username.length() < 3 || username.length() > 32) {
                        respond(exchange, 400, Map.of("error", "Username must be 3-32 characters."));
                        return;
                    }
                    if (password.length() < 10) {
                        respond(exchange, 400, Map.of("error", "Password must be at least 10 characters."));
                        return;
                    }
                    AdminRole role = AdminRole.parse(required(body, "role"), AdminRole.VIEWER);
                    String salt = PasswordHasher.newSalt();
                    String hash = PasswordHasher.hash(password, salt, PasswordHasher.DEFAULT_ITERATIONS);
                    database.createAdminUser(username, hash, salt, PasswordHasher.DEFAULT_ITERATIONS,
                            role.name(), System.currentTimeMillis());
                }
                case "ROLE" -> database.updateAdminUser(Long.parseLong(required(body, "id")),
                        AdminRole.parse(required(body, "role"), AdminRole.VIEWER).name(), null, null, 0,
                        System.currentTimeMillis());
                case "PASSWORD" -> {
                    String password = required(body, "password");
                    if (password.length() < 10) {
                        respond(exchange, 400, Map.of("error", "Password must be at least 10 characters."));
                        return;
                    }
                    String salt = PasswordHasher.newSalt();
                    String hash = PasswordHasher.hash(password, salt, PasswordHasher.DEFAULT_ITERATIONS);
                    database.updateAdminUser(Long.parseLong(required(body, "id")),
                            AdminRole.parse(required(body, "role"), AdminRole.VIEWER).name(), hash, salt,
                            PasswordHasher.DEFAULT_ITERATIONS, System.currentTimeMillis());
                }
                case "DELETE" -> {
                    long id = Long.parseLong(required(body, "id"));
                    FbsmpDatabase.AdminUserRow user = database.adminUsers().stream()
                            .filter(candidate -> candidate.id() == id).findFirst().orElse(null);
                    if (user != null && user.role().equals(AdminRole.OWNER.name())
                            && database.adminUsers().stream().filter(candidate -> candidate.role().equals(AdminRole.OWNER.name())).count() <= 1) {
                        respond(exchange, 409, Map.of("error", "Cannot remove the last owner account."));
                        return;
                    }
                    database.deleteAdminUser(id);
                }
                default -> {
                    respond(exchange, 400, Map.of("error", "Unknown admin action."));
                    return;
                }
            }
            audit(session, "ADMIN_" + action, optional(body, "username"), "", optional(body, "role"));
            respond(exchange, 200, Map.of("message", "Admin account updated."));
        } catch (NumberFormatException exception) {
            respond(exchange, 400, Map.of("error", "A numeric field was not a number."));
        } catch (SQLException exception) {
            respond(exchange, 503, Map.of("error", "Database unavailable."));
        }
    }

    // ------------------------------------------------------------------
    // Backups, config, audit helpers
    // ------------------------------------------------------------------

    private void backups(HttpExchange exchange) throws IOException {
        Path directory = plugin.getDataFolder().toPath().resolve("backups");
        if (!Files.isDirectory(directory)) {
            respond(exchange, 200, List.of());
            return;
        }
        try (var files = Files.list(directory)) {
            List<Map<String, Object>> rows = files
                    .filter(path -> path.getFileName().toString().matches("fbsmp-[0-9]{8}-[0-9]{6}\\.sqlite"))
                    .sorted(java.util.Comparator.reverseOrder())
                    .limit(50)
                    .map(path -> {
                        try {
                            return Map.<String, Object>of("name", path.getFileName().toString(),
                                    "sizeBytes", Files.size(path),
                                    "modifiedAt", Files.getLastModifiedTime(path).toMillis());
                        } catch (IOException exception) {
                            return Map.<String, Object>of("name", path.getFileName().toString(),
                                    "sizeBytes", 0L, "modifiedAt", 0L);
                        }
                    }).toList();
            respond(exchange, 200, rows);
        }
    }

    private void configuration(HttpExchange exchange) throws IOException {
        try {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("database", "SQLite");
            values.put("economyStartingBalance", Money.format(plugin.getConfig().getLong("economy.starting-balance", 0)));
            values.put("calendarDayLengthMinutes", plugin.getConfig().getInt("calendar.day-length-minutes", 30));
            values.put("defaultHomeLimit", plugin.getConfig().getInt("homes.default-limit", 3));
            values.put("backupRetentionCount", plugin.getConfig().getInt("backups.retention-count", 20));
            values.put("adminPanelBindAddress", plugin.getConfig().getString("admin-panel.bind-address", "127.0.0.1"));
            values.put("adminPanelPort", plugin.getConfig().getInt("admin-panel.port", 8765));
            values.put("timezone", database.globalSetting("timezone", "Asia/Kolkata"));
            values.put("maintenance", maintenanceMode());
            respond(exchange, 200, values);
        } catch (SQLException exception) {
            respond(exchange, 503, Map.of("error", "Database unavailable."));
        }
    }

    private boolean maintenanceMode() {
        try {
            return "on".equalsIgnoreCase(database.globalSetting("maintenance", "off"));
        } catch (SQLException exception) {
            return false;
        }
    }

    private void audit(Session session, String action, String target, String before, String after) {
        try {
            database.writeAudit(actorUuid(session), session.username, action, target, before, after, "", System.currentTimeMillis());
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not write admin audit row: " + exception.getMessage());
        }
    }

    private static UUID actorUuid(Session session) {
        return UUID.nameUUIDFromBytes(("FB-SMP-DASHBOARD:" + session.username).getBytes(StandardCharsets.UTF_8));
    }

    private Optional<UUID> resolvePlayer(String name) throws SQLException {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        Optional<FbsmpDatabase.PlayerRow> known = database.playerByName(name);
        if (known.isPresent()) {
            return Optional.of(UUID.fromString(known.get().uuid()));
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(name);
        return offline == null ? Optional.empty() : Optional.of(offline.getUniqueId());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void respondDatabase(HttpExchange exchange, DatabaseList query) throws IOException {
        try {
            respond(exchange, 200, query.fetch(100));
        } catch (SQLException exception) {
            respond(exchange, 503, Map.of("error", "Database unavailable."));
        }
    }

    private static <T> T safe(SqlSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (SQLException exception) {
            return null;
        }
    }

    @FunctionalInterface
    private interface DatabaseList {
        Object fetch(int limit) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private static boolean isLocalRequest(HttpExchange exchange) {
        InetAddress remote = exchange.getRemoteAddress().getAddress();
        return remote != null && remote.isLoopbackAddress();
    }

    private boolean isProxiedRequest(HttpExchange exchange) {
        if (!plugin.getConfig().getBoolean("admin-panel.trust-proxy", false)) {
            return false;
        }
        String remote = exchange.getRemoteAddress().getAddress().getHostAddress();
        return plugin.getConfig().getStringList("admin-panel.trusted-proxies").contains(remote);
    }

    /** Marks the session cookie Secure whenever the request arrived over TLS. */
    private static String secureFlag(HttpExchange exchange) {
        String proto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        return proto != null && proto.equalsIgnoreCase("https") ? "; Secure" : "";
    }

    private static boolean validHost(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.startsWith("127.0.0.1:") || normalized.startsWith("localhost:")
                || normalized.startsWith("[::1]:") || normalized.equals("127.0.0.1")
                || normalized.equals("localhost") || normalized.startsWith("[::1]");
    }

    private static String query(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return null;
        }
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) {
                return parts.length == 2 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }

    private JsonObject readJson(HttpExchange exchange) throws IOException {
        byte[] body = requestBody.get();
        if (body == null) {
            body = exchange.getRequestBody().readNBytes(65537);
            requestBody.set(body.length > 65536 ? new byte[0] : body);
        }
        if (body.length > 65536) {
            throw new IOException("Request body exceeds limit.");
        }
        if (body.length == 0) {
            return new JsonObject();
        }
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /**
     * Reads and discards a small request body exactly once per exchange. Handlers
     * that reject a request before reading it still need to consume the body, or
     * the JDK server resets the connection before the error response is delivered.
     */
    private void drain(HttpExchange exchange) {
        if (requestBody.get() != null) {
            return;
        }
        try {
            byte[] body = exchange.getRequestBody().readNBytes(65537);
            requestBody.set(body.length > 65536 ? new byte[0] : body);
        } catch (IOException ignored) {
            requestBody.set(new byte[0]);
        }
    }

    private static String required(JsonObject body, String key) {
        if (!body.has(key) || body.get(key).isJsonNull()) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return body.get(key).getAsString();
    }

    private static String optional(JsonObject body, String key) {
        return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsString() : "";
    }

    private static double parseDouble(JsonObject body, String key) {
        return Double.parseDouble(required(body, key));
    }

    private void sendResource(HttpExchange exchange, String resource, String contentType) throws IOException {
        try (var input = plugin.getResource(resource)) {
            if (input == null) {
                respond(exchange, 404, Map.of("error", "Panel files are missing."));
                return;
            }
            byte[] bytes = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; connect-src 'self'; style-src 'self' 'unsafe-inline'; "
                            + "script-src 'self' 'unsafe-inline'; img-src 'self' data:; object-src 'none'; "
                            + "base-uri 'none'; frame-ancestors 'none'");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static String cookie(HttpExchange exchange, String name) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null) {
            return null;
        }
        for (String part : cookieHeader.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals(name)) {
                return pair[1];
            }
        }
        return null;
    }

    private static void respond(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] response = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
    }

    private static String loadOrCreateToken(Path path) throws IOException {
        if (Files.exists(path)) {
            String token = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (token.length() < 32) {
                throw new IOException("The admin token file must contain at least 32 characters.");
            }
            return token;
        }
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(36));
        Files.createDirectories(path.getParent());
        Files.writeString(path, token + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows filesystems do not expose POSIX permissions.
        }
        return token;
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(1);
        }
        console.uninstall();
        executor.shutdownNow();
        sessions.clear();
        loginWindows.clear();
    }

    private record Session(String csrfToken, long expiresAt, String username, AdminRole role) {}
    private record LoginWindow(long startedAt, int attempts) {}

    private static final class HttpError extends RuntimeException {
        private final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
