package net.firebreadmc.fbsmp.paper.admin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.firebreadmc.fbsmp.common.Money;
import net.firebreadmc.fbsmp.paper.FbsmpPlugin;
import net.firebreadmc.fbsmp.paper.data.FbsmpDatabase;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PrivateAdminPanel implements AutoCloseable {
    private static final long SESSION_LIFETIME_MILLIS = 30 * 60 * 1000L;
    private static final Gson GSON = new Gson();

    private final FbsmpPlugin plugin;
    private final FbsmpDatabase database;
    private final String adminToken;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, LoginWindow> loginWindows = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "fbsmp-admin-panel");
        thread.setDaemon(true);
        return thread;
    });
    private HttpServer server;

    public PrivateAdminPanel(FbsmpPlugin plugin, FbsmpDatabase database) throws IOException {
        this.plugin = plugin;
        this.database = database;
        this.adminToken = loadOrCreateToken(plugin.getDataFolder().toPath().resolve("admin-token"));
    }

    public void start() throws IOException {
        String bindHost = plugin.getConfig().getString("admin-panel.bind-address", "127.0.0.1");
        InetAddress bindAddress = InetAddress.getByName(bindHost);
        if (!bindAddress.isLoopbackAddress()) {
            throw new IllegalArgumentException("Admin panel bind address must be loopback-only.");
        }
        int port = plugin.getConfig().getInt("admin-panel.port", 8765);
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Admin panel port must be between 1024 and 65535.");
        }
        server = HttpServer.create(new InetSocketAddress(bindAddress, port), 32);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
        plugin.getLogger().info("Private admin panel listening on " + bindAddress.getHostAddress() + ":" + port
                + ". Initial access token is stored in the plugin data folder's admin-token file.");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!isLocalRequest(exchange) || !validHost(exchange)) {
                respond(exchange, 403, Map.of("error", "Local access only."));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (path.equals("/") && method.equals("GET")) {
                sendResource(exchange, "panel/index.html", "text/html; charset=utf-8");
                return;
            }
            if (path.equals("/api/login") && method.equals("POST")) {
                login(exchange);
                return;
            }
            Session session = authenticatedSession(exchange);
            if (session == null) {
                respond(exchange, 401, Map.of("error", "Sign in to continue."));
                return;
            }
            if (path.equals("/api/session") && method.equals("GET")) {
                respond(exchange, 200, Map.of("csrfToken", session.csrfToken));
                return;
            }
            if (path.equals("/api/status") && method.equals("GET")) {
                status(exchange);
                return;
            }
            if (path.equals("/api/players") && method.equals("GET")) {
                players(exchange);
                return;
            }
            if (path.equals("/api/transactions") && method.equals("GET")) {
                transactions(exchange);
                return;
            }
            if (path.equals("/api/loans") && method.equals("GET")) {
                respondDatabase(exchange, database::adminLoans);
                return;
            }
            if (path.equals("/api/mortgages") && method.equals("GET")) {
                respondDatabase(exchange, database::adminMortgages);
                return;
            }
            if (path.equals("/api/auctions") && method.equals("GET")) {
                respondDatabase(exchange, database::adminAuctions);
                return;
            }
            if (path.equals("/api/orders") && method.equals("GET")) {
                respondDatabase(exchange, database::adminOrders);
                return;
            }
            if (path.equals("/api/moderation") && method.equals("GET")) {
                respondDatabase(exchange, database::recentModeration);
                return;
            }
            if (path.equals("/api/logs") && method.equals("GET")) {
                respondDatabase(exchange, database::recentAuditEvents);
                return;
            }
            if (path.equals("/api/backups") && method.equals("GET")) {
                backups(exchange);
                return;
            }
            if (path.equals("/api/config") && method.equals("GET")) {
                configuration(exchange);
                return;
            }
            if (path.equals("/api/backup") && method.equals("POST")) {
                if (!validCsrf(exchange, session)) {
                    respond(exchange, 403, Map.of("error", "CSRF validation failed."));
                    return;
                }
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        plugin.backupDatabase();
                        plugin.getLogger().info("An administrator created a database backup from the local panel.");
                    } catch (Exception exception) {
                        plugin.getLogger().warning("Admin panel backup failed: " + exception.getMessage());
                    }
                });
                respond(exchange, 202, Map.of("message", "Backup queued."));
                return;
            }
            if (path.equals("/api/logout") && method.equals("POST")) {
                if (!validCsrf(exchange, session)) {
                    respond(exchange, 403, Map.of("error", "CSRF validation failed."));
                    return;
                }
                String sessionId = cookie(exchange, "FBSMP_SESSION");
                if (sessionId != null) {
                    sessions.remove(sessionId);
                }
                exchange.getResponseHeaders().add("Set-Cookie",
                        "FBSMP_SESSION=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");
                respond(exchange, 200, Map.of("message", "Signed out."));
                return;
            }
            respond(exchange, 404, Map.of("error", "Not found."));
        } catch (Exception exception) {
            plugin.getLogger().warning("Admin panel request failed: " + exception.getMessage());
            try {
                respond(exchange, 500, Map.of("error", "The request could not be completed."));
            } catch (IOException ignored) {
                // The client may already have disconnected.
            }
        }
    }

    private void login(HttpExchange exchange) throws IOException {
        String client = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (!allowLogin(client)) {
            respond(exchange, 429, Map.of("error", "Too many login attempts. Wait one minute and try again."));
            return;
        }
        JsonObject input = readJson(exchange);
        String supplied = input.has("token") ? input.get("token").getAsString() : "";
        if (!constantTimeEquals(adminToken, supplied)) {
            respond(exchange, 401, Map.of("error", "The access token is not valid."));
            return;
        }
        byte[] sessionBytes = randomBytes(32);
        byte[] csrfBytes = randomBytes(24);
        String sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(sessionBytes);
        String csrfToken = Base64.getUrlEncoder().withoutPadding().encodeToString(csrfBytes);
        sessions.put(sessionId, new Session(csrfToken, System.currentTimeMillis() + SESSION_LIFETIME_MILLIS));
        exchange.getResponseHeaders().add("Set-Cookie",
                "FBSMP_SESSION=" + sessionId + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=1800");
        respond(exchange, 200, Map.of("csrfToken", csrfToken));
    }

    private void status(HttpExchange exchange) throws IOException {
        try {
            FbsmpDatabase.AdminSnapshot snapshot = database.adminSnapshot();
            respond(exchange, 200, Map.ofEntries(
                    Map.entry("server", "FB-SMP"),
                    Map.entry("version", plugin.getDescription().getVersion()),
                    Map.entry("status", "online"),
                    Map.entry("uptimeSeconds", plugin.uptimeSeconds()),
                    Map.entry("playerRecords", snapshot.playerCount()),
                    Map.entry("totalBalance", Money.format(snapshot.totalBalanceMinor())),
                    Map.entry("transactionCount", snapshot.transactionCount()),
                    Map.entry("onlinePlayers", plugin.getServer().getOnlinePlayers().size()),
                    Map.entry("maximumPlayers", plugin.getServer().getMaxPlayers()),
                    Map.entry("heapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()),
                    Map.entry("heapMaxBytes", Runtime.getRuntime().maxMemory()),
                    Map.entry("processors", Runtime.getRuntime().availableProcessors()),
                    Map.entry("serverTime", Instant.now().toString())
            ));
        } catch (SQLException exception) {
            respond(exchange, 503, Map.of("error", "Database unavailable."));
        }
    }

    private void players(HttpExchange exchange) throws IOException {
        try {
            respond(exchange, 200, database.topBalances(50));
        } catch (SQLException exception) {
            respond(exchange, 503, Map.of("error", "Database unavailable."));
        }
    }

    private void transactions(HttpExchange exchange) throws IOException {
        try {
            respond(exchange, 200, database.recentTransactions(50));
        } catch (SQLException exception) {
            respond(exchange, 503, Map.of("error", "Database unavailable."));
        }
    }

    private void respondDatabase(HttpExchange exchange, DatabaseList query) throws IOException {
        try {
            respond(exchange, 200, query.fetch(100));
        } catch (SQLException exception) {
            respond(exchange, 503, Map.of("error", "Database unavailable."));
        }
    }

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
        respond(exchange, 200, Map.ofEntries(
                Map.entry("database", "SQLite"),
                Map.entry("economyStartingBalance", Money.format(plugin.getConfig().getLong("economy.starting-balance", 0))),
                Map.entry("calendarDayLengthMinutes", plugin.getConfig().getInt("calendar.day-length-minutes", 30)),
                Map.entry("specialMonthDays", plugin.getConfig().getInt("calendar.special-month-days", 29)),
                Map.entry("defaultHomeLimit", plugin.getConfig().getInt("homes.default-limit", 3)),
                Map.entry("teleportRequestExpirySeconds", plugin.getConfig().getInt("teleport.request-expiry-seconds", 60)),
                Map.entry("teleportCooldownSeconds", plugin.getConfig().getInt("teleport.cooldown-seconds", 30)),
                Map.entry("randomTeleportMinRadius", plugin.getConfig().getInt("teleport.random.min-radius", 500)),
                Map.entry("randomTeleportMaxRadius", plugin.getConfig().getInt("teleport.random.max-radius", 5000)),
                Map.entry("starterKitEnabled", plugin.getConfig().getBoolean("starter-kit.enabled", true)),
                Map.entry("adminPanelBindAddress", plugin.getConfig().getString("admin-panel.bind-address", "127.0.0.1")),
                Map.entry("adminPanelPort", plugin.getConfig().getInt("admin-panel.port", 8765)),
                Map.entry("backupRetentionCount", plugin.getConfig().getInt("backups.retention-count", 20)),
                Map.entry("publicConfigurationOnly", true)
        ));
    }

    @FunctionalInterface
    private interface DatabaseList {
        Object fetch(int limit) throws SQLException;
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

    private boolean validCsrf(HttpExchange exchange, Session session) {
        String supplied = exchange.getRequestHeaders().getFirst("X-CSRF-Token");
        return supplied != null && constantTimeEquals(session.csrfToken, supplied);
    }

    private boolean allowLogin(String key) {
        long now = System.currentTimeMillis();
        LoginWindow window = loginWindows.compute(key, (ignored, current) ->
                current == null || now - current.startedAt >= 60_000L
                        ? new LoginWindow(now, 1)
                        : new LoginWindow(current.startedAt, current.attempts + 1));
        return window.attempts <= 8;
    }

    private static boolean isLocalRequest(HttpExchange exchange) {
        InetAddress remote = exchange.getRemoteAddress().getAddress();
        return remote != null && remote.isLoopbackAddress();
    }

    private static boolean validHost(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("127.0.0.1:") || normalized.startsWith("localhost:")
                || normalized.startsWith("[::1]:") || normalized.equals("127.0.0.1")
                || normalized.equals("localhost") || normalized.startsWith("[::1]");
    }

    private static JsonObject readJson(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(8193);
        if (body.length > 8192) {
            throw new IOException("Request body exceeds limit.");
        }
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
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
        executor.shutdownNow();
        sessions.clear();
        loginWindows.clear();
    }

    private record Session(String csrfToken, long expiresAt) {}
    private record LoginWindow(long startedAt, int attempts) {}
}