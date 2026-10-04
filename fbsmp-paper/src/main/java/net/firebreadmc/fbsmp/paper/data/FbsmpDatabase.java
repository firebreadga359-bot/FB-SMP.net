package net.firebreadmc.fbsmp.paper.data;

import net.firebreadmc.fbsmp.common.Money;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class FbsmpDatabase implements AutoCloseable {
    private final Connection connection;
    private final Object lock = new Object();

    public FbsmpDatabase(Path databaseFile, ClassLoader resourceLoader) throws SQLException, IOException {
        Files.createDirectories(databaseFile.toAbsolutePath().getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=NORMAL");
        }
        migrate(resourceLoader);
    }

    private void migrate(ClassLoader resourceLoader) throws SQLException, IOException {
        synchronized (lock) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS schema_migrations (
                            version INTEGER PRIMARY KEY,
                            applied_at INTEGER NOT NULL
                        )
                        """);
            }
        }
        applyMigration(resourceLoader, 1, "db/migration/V1__initial.sql");
        applyMigration(resourceLoader, 2, "db/migration/V2__transactions_and_mailbox.sql");
        applyMigration(resourceLoader, 3, "db/migration/V3__sell_escrow.sql");
        synchronized (lock) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT OR IGNORE INTO calendar_state(key, value)
                        VALUES ('start_epoch_ms', CAST(strftime('%s','now') * 1000 AS TEXT))
                        """);
            }
        }
    }

    private void applyMigration(ClassLoader resourceLoader, int version, String resource)
            throws SQLException, IOException {
        try (InputStream stream = resourceLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Missing database migration " + resource);
            }
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            synchronized (lock) {
                if (versionApplied(version)) {
                    return;
                }
                boolean previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    for (String sql : migration.split(";")) {
                        if (!sql.isBlank()) {
                            statement.execute(sql);
                        }
                    }
                    try (PreparedStatement record = connection.prepareStatement(
                            "INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)")) {
                        record.setInt(1, version);
                        record.setLong(2, System.currentTimeMillis());
                        record.executeUpdate();
                    }
                    connection.commit();
                } catch (SQLException exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(previousAutoCommit);
                }
            }
        }
    }

    private boolean versionApplied(int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM schema_migrations WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public void ensurePlayer(UUID playerId, String playerName, long now, long startingBalance) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO players(uuid, last_name, balance_minor, first_join_at, last_seen_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET last_name = excluded.last_name, last_seen_at = excluded.last_seen_at
                    """)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, playerName);
                statement.setLong(3, startingBalance);
                statement.setLong(4, now);
                statement.setLong(5, now);
                statement.executeUpdate();
            }
        }
    }

    public long balance(UUID playerId) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT balance_minor FROM players WHERE uuid = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getLong(1) : 0L;
                }
            }
        }
    }

    public boolean transfer(UUID sender, String senderName, UUID receiver, String receiverName,
                            long amountMinor, long now) throws SQLException {
        return transfer(sender, senderName, receiver, receiverName, amountMinor, "PLAYER_TRANSFER",
                "Player payment", now);
    }

    public boolean transfer(UUID sender, String senderName, UUID receiver, String receiverName,
                            long amountMinor, String transactionType, String description, long now)
            throws SQLException {
        if (sender.equals(receiver)) {
            throw new IllegalArgumentException("You cannot pay yourself.");
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Amount must be positive.");
        }
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ensurePlayer(sender, senderName, now, 0);
                ensurePlayer(receiver, receiverName, now, 0);
                try (PreparedStatement debit = connection.prepareStatement("""
                        UPDATE players SET balance_minor = balance_minor - ?
                        WHERE uuid = ? AND balance_minor >= ?
                        """)) {
                    debit.setLong(1, amountMinor);
                    debit.setString(2, sender.toString());
                    debit.setLong(3, amountMinor);
                    if (debit.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                try (PreparedStatement credit = connection.prepareStatement(
                        "UPDATE players SET balance_minor = balance_minor + ? WHERE uuid = ?")) {
                    credit.setLong(1, amountMinor);
                    credit.setString(2, receiver.toString());
                    credit.executeUpdate();
                }
                try (PreparedStatement log = connection.prepareStatement("""
                        INSERT INTO economy_transactions
                        (id, sender_uuid, receiver_uuid, amount_minor, transaction_type, description, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    log.setString(1, UUID.randomUUID().toString());
                    log.setString(2, sender.toString());
                    log.setString(3, receiver.toString());
                    log.setLong(4, amountMinor);
                    log.setString(5, transactionType);
                    log.setString(6, description);
                    log.setLong(7, now);
                    log.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public List<PlayerBalance> topBalances(int limit) throws SQLException {
        synchronized (lock) {
            List<PlayerBalance> balances = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT last_name, balance_minor FROM players
                    ORDER BY balance_minor DESC, last_name COLLATE NOCASE LIMIT ?
                    """)) {
                statement.setInt(1, Math.max(1, Math.min(50, limit)));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        balances.add(new PlayerBalance(result.getString(1), result.getLong(2)));
                    }
                }
            }
            return balances;
        }
    }

    public boolean claimStarterKit(UUID playerId) throws SQLException {
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE players SET starter_claimed = 1 WHERE uuid = ? AND starter_claimed = 0
                    """)) {
                statement.setString(1, playerId.toString());
                if (statement.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
                List<ItemStack> kit = List.of(
                        new ItemStack(Material.CHAINMAIL_HELMET),
                        new ItemStack(Material.CHAINMAIL_CHESTPLATE),
                        new ItemStack(Material.CHAINMAIL_LEGGINGS),
                        new ItemStack(Material.CHAINMAIL_BOOTS),
                        new ItemStack(Material.BAKED_POTATO, 16),
                        new ItemStack(Material.OAK_LOG, 16),
                        new ItemStack(Material.COBBLESTONE, 32),
                        new ItemStack(Material.WOODEN_PICKAXE),
                        new ItemStack(Material.WOODEN_AXE),
                        new ItemStack(Material.WOODEN_SHOVEL),
                        new ItemStack(Material.TORCH, 16)
                );
                long now = System.currentTimeMillis();
                for (ItemStack item : kit) {
                    String data = java.util.Base64.getEncoder().encodeToString(item.serializeAsBytes());
                    insertMailbox(playerId, data, now);
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public void saveHome(UUID playerId, String name, Location location, long now) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO homes(player_uuid, name, world, x, y, z, yaw, pitch, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(player_uuid, name) DO UPDATE SET world=excluded.world, x=excluded.x, y=excluded.y,
                    z=excluded.z, yaw=excluded.yaw, pitch=excluded.pitch, created_at=excluded.created_at
                    """)) {
                bindLocation(statement, 1, playerId.toString(), name, location, now);
                statement.executeUpdate();
            }
        }
    }

    public Optional<SavedLocation> home(UUID playerId, String name) throws SQLException {
        return location("SELECT name, world, x, y, z, yaw, pitch FROM homes WHERE player_uuid = ? AND name = ?",
                playerId.toString(), name);
    }

    public List<String> homes(UUID playerId) throws SQLException {
        synchronized (lock) {
            List<String> names = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT name FROM homes WHERE player_uuid = ? ORDER BY name COLLATE NOCASE")) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        names.add(result.getString(1));
                    }
                }
            }
            return names;
        }
    }

    public boolean deleteHome(UUID playerId, String name) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM homes WHERE player_uuid = ? AND name = ?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, name);
                return statement.executeUpdate() > 0;
            }
        }
    }

    public void saveWarp(String name, Location location, UUID staffId, long now) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO warps(name, world, x, y, z, yaw, pitch, created_by, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(name) DO UPDATE SET world=excluded.world, x=excluded.x, y=excluded.y,
                    z=excluded.z, yaw=excluded.yaw, pitch=excluded.pitch, created_by=excluded.created_by,
                    updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, name);
                statement.setString(2, location.getWorld().getName());
                statement.setDouble(3, location.getX());
                statement.setDouble(4, location.getY());
                statement.setDouble(5, location.getZ());
                statement.setFloat(6, location.getYaw());
                statement.setFloat(7, location.getPitch());
                statement.setString(8, staffId.toString());
                statement.setLong(9, now);
                statement.executeUpdate();
            }
        }
    }

    public Optional<SavedLocation> warp(String name) throws SQLException {
        return location("SELECT name, world, x, y, z, yaw, pitch FROM warps WHERE name = ?", name);
    }

    public boolean deleteWarp(String name) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM warps WHERE name = ?")) {
                statement.setString(1, name);
                return statement.executeUpdate() > 0;
            }
        }
    }

    public void setSetting(UUID playerId, String key, String value, long now) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO settings(player_uuid, setting, value, updated_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT(player_uuid, setting) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, key);
                statement.setString(3, value);
                statement.setLong(4, now);
                statement.executeUpdate();
            }
        }
    }

    public String setting(UUID playerId, String key, String defaultValue) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT value FROM settings WHERE player_uuid = ? AND setting = ?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, key);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getString(1) : defaultValue;
                }
            }
        }
    }

    public boolean toggleIgnore(UUID playerId, UUID ignoredId, long now) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement find = connection.prepareStatement(
                    "SELECT 1 FROM ignored_players WHERE player_uuid = ? AND ignored_uuid = ?")) {
                find.setString(1, playerId.toString());
                find.setString(2, ignoredId.toString());
                try (ResultSet result = find.executeQuery()) {
                    if (result.next()) {
                        try (PreparedStatement delete = connection.prepareStatement(
                                "DELETE FROM ignored_players WHERE player_uuid = ? AND ignored_uuid = ?")) {
                            delete.setString(1, playerId.toString());
                            delete.setString(2, ignoredId.toString());
                            delete.executeUpdate();
                        }
                        return false;
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO ignored_players(player_uuid, ignored_uuid, created_at) VALUES (?, ?, ?)")) {
                insert.setString(1, playerId.toString());
                insert.setString(2, ignoredId.toString());
                insert.setLong(3, now);
                insert.executeUpdate();
            }
            return true;
        }
    }

    public boolean ignores(UUID playerId, UUID otherId) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM ignored_players WHERE player_uuid = ? AND ignored_uuid = ?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, otherId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next();
                }
            }
        }
    }

    public PlayerStats stats(UUID playerId) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT balance_minor, playtime_seconds, blocks_placed, blocks_broken, deaths, last_seen_at
                    FROM players WHERE uuid = ?
                    """)) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return new PlayerStats(0, 0, 0, 0, 0, 0);
                    }
                    return new PlayerStats(result.getLong(1), result.getLong(2), result.getLong(3),
                            result.getLong(4), result.getLong(5), result.getLong(6));
                }
            }
        }
    }

    public void recordPlaytime(UUID playerId, long seconds) throws SQLException {
        if (seconds <= 0) {
            return;
        }
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE players SET playtime_seconds = playtime_seconds + ? WHERE uuid = ?")) {
                statement.setLong(1, seconds);
                statement.setString(2, playerId.toString());
                statement.executeUpdate();
            }
        }
    }

    public void recordActivity(UUID playerId, long seconds, long placed, long broken, long deaths)
            throws SQLException {
        if (seconds <= 0 && placed <= 0 && broken <= 0 && deaths <= 0) {
            return;
        }
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE players SET playtime_seconds = playtime_seconds + ?,
                    blocks_placed = blocks_placed + ?, blocks_broken = blocks_broken + ?,
                    deaths = deaths + ? WHERE uuid = ?
                    """)) {
                statement.setLong(1, Math.max(0, seconds));
                statement.setLong(2, Math.max(0, placed));
                statement.setLong(3, Math.max(0, broken));
                statement.setLong(4, Math.max(0, deaths));
                statement.setString(5, playerId.toString());
                statement.executeUpdate();
            }
        }
    }

    public void recordDeath(UUID playerId) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE players SET deaths = deaths + 1 WHERE uuid = ?")) {
                statement.setString(1, playerId.toString());
                statement.executeUpdate();
            }
        }
    }

    public void recordBlock(UUID playerId, boolean placed) throws SQLException {
        String column = placed ? "blocks_placed" : "blocks_broken";
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE players SET " + column + " = " + column + " + 1 WHERE uuid = ?")) {
                statement.setString(1, playerId.toString());
                statement.executeUpdate();
            }
        }
    }

    public long recordModeration(UUID playerId, UUID staffId, String action, String reason,
                                 Long expiresAt, long now) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO moderation_records(player_uuid, staff_uuid, action, reason, expires_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, staffId.toString());
                statement.setString(3, action);
                statement.setString(4, reason);
                if (expiresAt == null) {
                    statement.setNull(5, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(5, expiresAt);
                }
                statement.setLong(6, now);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : -1;
                }
            }
        }
    }

    public Optional<ModerationEntry> activeModeration(UUID playerId, String action, long now) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT action, reason, expires_at, created_at FROM moderation_records
                    WHERE player_uuid = ? AND action = ? AND (expires_at IS NULL OR expires_at > ?)
                    ORDER BY created_at DESC LIMIT 1
                    """)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, action);
                statement.setLong(3, now);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    long expiration = result.getLong(3);
                    Long expiresAt = result.wasNull() ? null : expiration;
                    return Optional.of(new ModerationEntry(playerId, result.getString(1),
                            result.getString(2), expiresAt, result.getLong(4)));
                }
            }
        }
    }

    public List<ModerationEntry> recentModeration(int limit) throws SQLException {
        synchronized (lock) {
            List<ModerationEntry> records = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_uuid, action, reason, expires_at, created_at
                    FROM moderation_records ORDER BY created_at DESC LIMIT ?
                    """)) {
                statement.setInt(1, Math.max(1, Math.min(100, limit)));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        long expiration = result.getLong(4);
                        Long expiresAt = result.wasNull() ? null : expiration;
                        records.add(new ModerationEntry(UUID.fromString(result.getString(1)),
                                result.getString(2), result.getString(3), expiresAt, result.getLong(5)));
                    }
                }
            }
            return records;
        }
    }

    public String calendarStart() throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT value FROM calendar_state WHERE key = 'start_epoch_ms'");
                 ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : Long.toString(System.currentTimeMillis());
            }
        }
    }

    public void backupTo(Path destination) throws SQLException, IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        synchronized (lock) {
            if (Files.exists(destination)) {
                throw new IOException("Backup destination already exists.");
            }
            String escapedPath = destination.toAbsolutePath().toString().replace("'", "''");
            try (Statement statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + escapedPath + "'");
            }
        }
    }

    public void saveReplyTarget(UUID playerId, UUID targetId, long now) throws SQLException {
        setSetting(playerId, "last-message-target", targetId.toString(), now);
    }

    public Optional<UUID> replyTarget(UUID playerId) throws SQLException {
        String value = setting(playerId, "last-message-target", "");
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public boolean debit(UUID playerId, long amountMinor, String type, String description, long now)
            throws SQLException {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Amount must be positive.");
        }
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement debit = connection.prepareStatement(
                        "UPDATE players SET balance_minor = balance_minor - ? WHERE uuid = ? AND balance_minor >= ?")) {
                    debit.setLong(1, amountMinor);
                    debit.setString(2, playerId.toString());
                    debit.setLong(3, amountMinor);
                    if (debit.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                try (PreparedStatement log = connection.prepareStatement("""
                        INSERT INTO economy_transactions
                        (id, sender_uuid, receiver_uuid, amount_minor, transaction_type, description, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    log.setString(1, UUID.randomUUID().toString());
                    log.setString(2, playerId.toString());
                    log.setString(3, playerId.toString());
                    log.setLong(4, amountMinor);
                    log.setString(5, type);
                    log.setString(6, description);
                    log.setLong(7, now);
                    log.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public boolean credit(UUID playerId, long amountMinor, String type, String description, long now)
            throws SQLException {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Amount must be positive.");
        }
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement credit = connection.prepareStatement(
                        "UPDATE players SET balance_minor = balance_minor + ? WHERE uuid = ?")) {
                    credit.setLong(1, amountMinor);
                    credit.setString(2, playerId.toString());
                    if (credit.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                try (PreparedStatement log = connection.prepareStatement("""
                        INSERT INTO economy_transactions
                        (id, sender_uuid, receiver_uuid, amount_minor, transaction_type, description, created_at)
                        VALUES (?, NULL, ?, ?, ?, ?, ?)
                        """)) {
                    log.setString(1, UUID.randomUUID().toString());
                    log.setString(2, playerId.toString());
                    log.setLong(3, amountMinor);
                    log.setString(4, type);
                    log.setString(5, description);
                    log.setLong(6, now);
                    log.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public boolean debitAndMail(UUID playerId, long amountMinor, String itemData,
                                String description, long now) throws SQLException {
        if (amountMinor <= 0 || itemData == null || itemData.isBlank()) {
            throw new IllegalArgumentException("Purchase details are invalid.");
        }
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement debit = connection.prepareStatement("""
                        UPDATE players SET balance_minor = balance_minor - ?
                        WHERE uuid = ? AND balance_minor >= ?
                        """)) {
                    debit.setLong(1, amountMinor);
                    debit.setString(2, playerId.toString());
                    debit.setLong(3, amountMinor);
                    if (debit.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                insertTransaction(playerId.toString(), playerId.toString(), amountMinor,
                        "SHOP_PURCHASE", description, now);
                insertMailbox(playerId, itemData, now);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public boolean issueLoan(UUID playerId, long principalMinor, long interestMinor,
                             long maximumOutstanding, long dueAt, long now) throws SQLException {
        if (principalMinor <= 0 || interestMinor < 0) {
            throw new IllegalArgumentException("Loan values are invalid.");
        }
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement active = connection.prepareStatement("""
                        SELECT COALESCE(SUM(remaining_minor), 0) FROM loans
                        WHERE player_uuid = ? AND status IN ('ACTIVE','OVERDUE')
                        """)) {
                    active.setString(1, playerId.toString());
                    try (ResultSet result = active.executeQuery()) {
                        long outstanding = result.next() ? result.getLong(1) : 0;
                        if (outstanding + principalMinor + interestMinor > maximumOutstanding) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                long total = Math.addExact(principalMinor, interestMinor);
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO loans(player_uuid, principal_minor, interest_minor, remaining_minor,
                        due_at, status, created_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)
                        """)) {
                    insert.setString(1, playerId.toString());
                    insert.setLong(2, principalMinor);
                    insert.setLong(3, interestMinor);
                    insert.setLong(4, total);
                    insert.setLong(5, dueAt);
                    insert.setLong(6, now);
                    insert.executeUpdate();
                }
                try (PreparedStatement credit = connection.prepareStatement(
                        "UPDATE players SET balance_minor = balance_minor + ? WHERE uuid = ?")) {
                    credit.setLong(1, principalMinor);
                    credit.setString(2, playerId.toString());
                    if (credit.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                insertTransaction(null, playerId.toString(), principalMinor, "LOAN_DISBURSEMENT",
                        "Loan disbursement", now);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public LoanSummary loanSummary(UUID playerId) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*), COALESCE(SUM(remaining_minor), 0), MIN(due_at)
                    FROM loans WHERE player_uuid = ? AND status IN ('ACTIVE','OVERDUE')
                    """)) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return new LoanSummary(result.getInt(1), result.getLong(2), result.getLong(3));
                }
            }
        }
    }

    public long repayLoans(UUID playerId, long amountMinor, long now) throws SQLException {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Repayment must be positive.");
        }
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long balance;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT balance_minor FROM players WHERE uuid = ?")) {
                    statement.setString(1, playerId.toString());
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next() || result.getLong(1) < amountMinor) {
                            connection.rollback();
                            return -1;
                        }
                        balance = result.getLong(1);
                    }
                }
                long remainingPayment = amountMinor;
                try (PreparedStatement loans = connection.prepareStatement("""
                        SELECT id, remaining_minor FROM loans WHERE player_uuid = ?
                        AND status IN ('ACTIVE','OVERDUE') ORDER BY due_at, id
                        """)) {
                    loans.setString(1, playerId.toString());
                    try (ResultSet result = loans.executeQuery()) {
                        while (result.next() && remainingPayment > 0) {
                            long payment = Math.min(remainingPayment, result.getLong(2));
                            long newRemaining = result.getLong(2) - payment;
                            try (PreparedStatement update = connection.prepareStatement("""
                                    UPDATE loans SET remaining_minor = ?, status = ?
                                    WHERE id = ? AND player_uuid = ? AND status IN ('ACTIVE','OVERDUE')
                                    """)) {
                                update.setLong(1, newRemaining);
                                update.setString(2, newRemaining == 0 ? "PAID" : "ACTIVE");
                                update.setLong(3, result.getLong(1));
                                update.setString(4, playerId.toString());
                                update.executeUpdate();
                            }
                            remainingPayment -= payment;
                        }
                    }
                }
                long actualPayment = amountMinor - remainingPayment;
                if (actualPayment == 0) {
                    connection.rollback();
                    return 0;
                }
                try (PreparedStatement debit = connection.prepareStatement(
                        "UPDATE players SET balance_minor = balance_minor - ? WHERE uuid = ? AND balance_minor >= ?")) {
                    debit.setLong(1, actualPayment);
                    debit.setString(2, playerId.toString());
                    debit.setLong(3, actualPayment);
                    if (debit.executeUpdate() != 1) {
                        connection.rollback();
                        return -1;
                    }
                }
                insertTransaction(playerId.toString(), playerId.toString(), actualPayment,
                        "LOAN_REPAYMENT", "Loan repayment", now);
                connection.commit();
                return actualPayment;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public boolean createMortgage(UUID playerId, String assetKey, long assetPriceMinor, long downPaymentMinor,
                                  long interestMinor, int installments, long intervalMillis, long now)
            throws SQLException {
        if (assetKey == null || assetKey.isBlank() || assetPriceMinor <= 0 || downPaymentMinor < 0
                || downPaymentMinor >= assetPriceMinor || interestMinor < 0 || installments < 1
                || intervalMillis <= 0) {
            throw new IllegalArgumentException("Mortgage terms are invalid.");
        }
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement existing = connection.prepareStatement(
                        "SELECT 1 FROM property_ownership WHERE asset_key = ? COLLATE NOCASE")) {
                    existing.setString(1, assetKey);
                    try (ResultSet result = existing.executeQuery()) {
                        if (result.next()) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                if (downPaymentMinor > 0) {
                    try (PreparedStatement debit = connection.prepareStatement("""
                            UPDATE players SET balance_minor = balance_minor - ?
                            WHERE uuid = ? AND balance_minor >= ?
                            """)) {
                        debit.setLong(1, downPaymentMinor);
                        debit.setString(2, playerId.toString());
                        debit.setLong(3, downPaymentMinor);
                        if (debit.executeUpdate() != 1) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                long financed = assetPriceMinor - downPaymentMinor;
                long total = Math.addExact(financed, interestMinor);
                long installment = Math.floorDiv(Math.addExact(total, installments - 1L), installments);
                long mortgageId;
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO mortgages(player_uuid, asset_key, principal_minor, remaining_minor,
                        installment_minor, installments_left, next_due_at, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, playerId.toString());
                    insert.setString(2, assetKey);
                    insert.setLong(3, financed);
                    insert.setLong(4, total);
                    insert.setLong(5, installment);
                    insert.setInt(6, installments);
                    insert.setLong(7, Math.addExact(now, intervalMillis));
                    insert.setLong(8, now);
                    insert.executeUpdate();
                    try (ResultSet keys = insert.getGeneratedKeys()) {
                        if (!keys.next()) {
                            connection.rollback();
                            return false;
                        }
                        mortgageId = keys.getLong(1);
                    }
                }
                try (PreparedStatement ownership = connection.prepareStatement("""
                        INSERT INTO property_ownership(asset_key, owner_uuid, mortgage_id, acquired_at)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    ownership.setString(1, assetKey);
                    ownership.setString(2, playerId.toString());
                    ownership.setLong(3, mortgageId);
                    ownership.setLong(4, now);
                    ownership.executeUpdate();
                }
                if (downPaymentMinor > 0) {
                    insertTransaction(playerId.toString(), "SYSTEM", downPaymentMinor,
                            "MORTGAGE_DOWN_PAYMENT", "Mortgage purchase: " + assetKey, now);
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                if (exception instanceof SQLException sql && sql.getMessage() != null
                        && sql.getMessage().contains("UNIQUE constraint failed")) {
                    return false;
                }
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public MortgageSummary mortgageSummary(UUID playerId) throws SQLException {
        synchronized (lock) {
            List<MortgageAccount> accounts = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT asset_key, remaining_minor, next_due_at, installments_left, status
                    FROM mortgages WHERE player_uuid = ? AND status IN ('ACTIVE','OVERDUE')
                    ORDER BY next_due_at
                    """)) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        accounts.add(new MortgageAccount(result.getString(1), result.getLong(2),
                                result.getLong(3), result.getInt(4), result.getString(5)));
                    }
                }
            }
            long outstanding = accounts.stream().mapToLong(MortgageAccount::remainingMinor).sum();
            long nearestDue = accounts.isEmpty() ? 0 : accounts.get(0).nextDueAt();
            return new MortgageSummary(List.copyOf(accounts), outstanding, nearestDue);
        }
    }

    public MortgagePayment payMortgageInstallment(UUID playerId, long now, long intervalMillis)
            throws SQLException {
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long id;
                long remaining;
                long installment;
                int installmentsLeft;
                long dueAt;
                String asset;
                try (PreparedStatement find = connection.prepareStatement("""
                        SELECT id, asset_key, remaining_minor, installment_minor, installments_left, next_due_at
                        FROM mortgages WHERE player_uuid = ? AND status IN ('ACTIVE','OVERDUE')
                        ORDER BY next_due_at LIMIT 1
                        """)) {
                    find.setString(1, playerId.toString());
                    try (ResultSet result = find.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return null;
                        }
                        id = result.getLong(1);
                        asset = result.getString(2);
                        remaining = result.getLong(3);
                        installment = result.getLong(4);
                        installmentsLeft = result.getInt(5);
                        dueAt = result.getLong(6);
                    }
                }
                if (dueAt > now) {
                    connection.rollback();
                    return new MortgagePayment(asset, 0, remaining, dueAt, installmentsLeft);
                }
                long payment = Math.min(installment, remaining);
                try (PreparedStatement debit = connection.prepareStatement("""
                        UPDATE players SET balance_minor = balance_minor - ?
                        WHERE uuid = ? AND balance_minor >= ?
                        """)) {
                    debit.setLong(1, payment);
                    debit.setString(2, playerId.toString());
                    debit.setLong(3, payment);
                    if (debit.executeUpdate() != 1) {
                        connection.rollback();
                        return new MortgagePayment(asset, -1, remaining, dueAt, installmentsLeft);
                    }
                }
                long newRemaining = remaining - payment;
                int newInstallments = Math.max(0, installmentsLeft - 1);
                boolean paid = newRemaining == 0;
                long nextDue = paid ? dueAt : Math.addExact(Math.max(now, dueAt), intervalMillis);
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE mortgages SET remaining_minor = ?, installments_left = ?, next_due_at = ?,
                        status = ? WHERE id = ? AND player_uuid = ? AND status IN ('ACTIVE','OVERDUE')
                        """)) {
                    update.setLong(1, newRemaining);
                    update.setInt(2, newInstallments);
                    update.setLong(3, nextDue);
                    update.setString(4, paid ? "PAID" : "ACTIVE");
                    update.setLong(5, id);
                    update.setString(6, playerId.toString());
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return null;
                    }
                }
                insertTransaction(playerId.toString(), playerId.toString(), payment,
                        "MORTGAGE_PAYMENT", "Mortgage installment: " + asset, now);
                connection.commit();
                return new MortgagePayment(asset, payment, newRemaining, nextDue, newInstallments);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public long createAuction(UUID seller, String itemData, int quantity, long priceMinor,
                              long expiresAt, long now) throws SQLException {
        if (quantity <= 0 || priceMinor <= 0 || itemData == null || itemData.isBlank()) {
            throw new IllegalArgumentException("Auction listing is invalid.");
        }
        synchronized (lock) {
            try (PreparedStatement count = connection.prepareStatement("""
                    SELECT COUNT(*) FROM auctions WHERE seller_uuid = ? AND status = 'OPEN' AND expires_at > ?
                    """)) {
                count.setString(1, seller.toString());
                count.setLong(2, now);
                try (ResultSet result = count.executeQuery()) {
                    if (result.next() && result.getInt(1) >= 10) {
                        return -1;
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO auctions(seller_uuid, item_data, quantity, price_minor, status, expires_at, created_at)
                    VALUES (?, ?, ?, ?, 'OPEN', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, seller.toString());
                statement.setString(2, itemData);
                statement.setInt(3, quantity);
                statement.setLong(4, priceMinor);
                statement.setLong(5, expiresAt);
                statement.setLong(6, now);
                statement.executeUpdate();
                try (ResultSet result = statement.getGeneratedKeys()) {
                    return result.next() ? result.getLong(1) : -1;
                }
            }
        }
    }

    public List<AuctionListing> openAuctions(long now, int limit) throws SQLException {
        synchronized (lock) {
            List<AuctionListing> listings = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, seller_uuid, item_data, quantity, price_minor, expires_at
                    FROM auctions WHERE status = 'OPEN' AND expires_at > ?
                    ORDER BY created_at DESC LIMIT ?
                    """)) {
                statement.setLong(1, now);
                statement.setInt(2, Math.max(1, Math.min(100, limit)));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        listings.add(new AuctionListing(result.getLong(1), UUID.fromString(result.getString(2)),
                                result.getString(3), result.getInt(4), result.getLong(5), result.getLong(6)));
                    }
                }
            }
            return listings;
        }
    }

    public AuctionPurchase purchaseAuction(UUID buyer, long listingId, long now) throws SQLException {
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                AuctionListing listing;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT seller_uuid, item_data, quantity, price_minor, expires_at
                        FROM auctions WHERE id = ? AND status = 'OPEN'
                        """)) {
                    statement.setLong(1, listingId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next() || result.getLong(5) <= now) {
                            connection.rollback();
                            return null;
                        }
                        listing = new AuctionListing(listingId, UUID.fromString(result.getString(1)),
                                result.getString(2), result.getInt(3), result.getLong(4), result.getLong(5));
                    }
                }
                if (listing.seller().equals(buyer)) {
                    connection.rollback();
                    return null;
                }
                try (PreparedStatement debit = connection.prepareStatement("""
                        UPDATE players SET balance_minor = balance_minor - ?
                        WHERE uuid = ? AND balance_minor >= ?
                        """)) {
                    debit.setLong(1, listing.priceMinor());
                    debit.setString(2, buyer.toString());
                    debit.setLong(3, listing.priceMinor());
                    if (debit.executeUpdate() != 1) {
                        connection.rollback();
                        return null;
                    }
                }
                try (PreparedStatement credit = connection.prepareStatement(
                        "UPDATE players SET balance_minor = balance_minor + ? WHERE uuid = ?")) {
                    credit.setLong(1, listing.priceMinor());
                    credit.setString(2, listing.seller().toString());
                    if (credit.executeUpdate() != 1) {
                        connection.rollback();
                        return null;
                    }
                }
                try (PreparedStatement close = connection.prepareStatement("""
                        UPDATE auctions SET status = 'SOLD', buyer_uuid = ?, completed_at = ?
                        WHERE id = ? AND status = 'OPEN' AND expires_at > ?
                        """)) {
                    close.setString(1, buyer.toString());
                    close.setLong(2, now);
                    close.setLong(3, listingId);
                    close.setLong(4, now);
                    if (close.executeUpdate() != 1) {
                        connection.rollback();
                        return null;
                    }
                }
                insertTransaction(buyer.toString(), listing.seller().toString(), listing.priceMinor(),
                        "AUCTION_PURCHASE", "Auction listing #" + listingId, now);
                insertMailbox(buyer, listing.itemData(), now);
                connection.commit();
                return new AuctionPurchase(listing.seller(), listing.priceMinor(), listing.quantity());
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public boolean cancelAuction(UUID seller, long listingId, long now) throws SQLException {
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String itemData;
                try (PreparedStatement find = connection.prepareStatement("""
                        SELECT item_data FROM auctions WHERE id = ? AND seller_uuid = ? AND status = 'OPEN'
                        """)) {
                    find.setLong(1, listingId);
                    find.setString(2, seller.toString());
                    try (ResultSet result = find.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return false;
                        }
                        itemData = result.getString(1);
                    }
                }
                try (PreparedStatement close = connection.prepareStatement(
                        "UPDATE auctions SET status = 'CANCELLED', completed_at = ? WHERE id = ? AND status = 'OPEN'")) {
                    close.setLong(1, now);
                    close.setLong(2, listingId);
                    if (close.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                insertMailbox(seller, itemData, now);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public int expireAuctions(long now) throws SQLException {
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            int expired = 0;
            try {
                List<ExpiredAuction> listings = new ArrayList<>();
                try (PreparedStatement find = connection.prepareStatement("""
                        SELECT id, seller_uuid, item_data FROM auctions
                        WHERE status IN ('OPEN','CLAIMED') AND expires_at <= ?
                        """)) {
                    find.setLong(1, now);
                    try (ResultSet result = find.executeQuery()) {
                        while (result.next()) {
                            listings.add(new ExpiredAuction(result.getLong(1),
                                    UUID.fromString(result.getString(2)), result.getString(3)));
                        }
                    }
                }
                for (ExpiredAuction listing : listings) {
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE auctions SET status = 'EXPIRED', completed_at = ?
                            WHERE id = ? AND status IN ('OPEN','CLAIMED') AND expires_at <= ?
                            """)) {
                        update.setLong(1, now);
                        update.setLong(2, listing.id());
                        update.setLong(3, now);
                        if (update.executeUpdate() != 1) {
                            continue;
                        }
                    }
                    insertMailbox(listing.seller(), listing.itemData(), now);
                    expired++;
                }
                connection.commit();
                return expired;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public boolean createOrder(UUID requester, String itemKey, int quantity, long rewardMinor,
                               long expiresAt, long now) throws SQLException {
        if (quantity <= 0 || rewardMinor <= 0 || itemKey == null || itemKey.isBlank()) {
            throw new IllegalArgumentException("Order details are invalid.");
        }
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement count = connection.prepareStatement("""
                        SELECT COUNT(*) FROM community_orders WHERE requester_uuid = ?
                        AND status IN ('OPEN','CLAIMED','DELIVERED')
                        """)) {
                    count.setString(1, requester.toString());
                    try (ResultSet result = count.executeQuery()) {
                        if (result.next() && result.getInt(1) >= 5) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                try (PreparedStatement debit = connection.prepareStatement("""
                        UPDATE players SET balance_minor = balance_minor - ?
                        WHERE uuid = ? AND balance_minor >= ?
                        """)) {
                    debit.setLong(1, rewardMinor);
                    debit.setString(2, requester.toString());
                    debit.setLong(3, rewardMinor);
                    if (debit.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO community_orders
                        (requester_uuid, item_key, quantity, reward_minor, status, expires_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?)
                        """)) {
                    insert.setString(1, requester.toString());
                    insert.setString(2, itemKey);
                    insert.setInt(3, quantity);
                    insert.setLong(4, rewardMinor);
                    insert.setLong(5, expiresAt);
                    insert.setLong(6, now);
                    insert.setLong(7, now);
                    insert.executeUpdate();
                }
                insertTransaction(requester.toString(), requester.toString(), rewardMinor,
                        "ORDER_ESCROW", "Community order reward escrow", now);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public List<CommunityOrder> openOrders(long now, int limit) throws SQLException {
        synchronized (lock) {
            List<CommunityOrder> orders = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, requester_uuid, claimer_uuid, item_key, quantity, reward_minor, status, expires_at
                    FROM community_orders WHERE status = 'DELIVERED'
                    OR (status IN ('OPEN','CLAIMED') AND expires_at > ?)
                    ORDER BY created_at DESC LIMIT ?
                    """)) {
                statement.setLong(1, now);
                statement.setInt(2, Math.max(1, Math.min(100, limit)));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        String claimer = result.getString(3);
                        orders.add(new CommunityOrder(result.getLong(1), UUID.fromString(result.getString(2)),
                                claimer == null ? null : UUID.fromString(claimer), result.getString(4),
                                result.getInt(5), result.getLong(6), result.getString(7), result.getLong(8)));
                    }
                }
            }
            return orders;
        }
    }

    public boolean claimOrder(UUID claimer, long orderId, long now) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE community_orders SET status = 'CLAIMED', claimer_uuid = ?, updated_at = ?
                    WHERE id = ? AND requester_uuid <> ? AND status = 'OPEN' AND expires_at > ?
                    """)) {
                statement.setString(1, claimer.toString());
                statement.setLong(2, now);
                statement.setLong(3, orderId);
                statement.setString(4, claimer.toString());
                statement.setLong(5, now);
                return statement.executeUpdate() == 1;
            }
        }
    }

    public boolean deliverOrder(UUID claimer, long orderId, String itemData, long now) throws SQLException {
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                UUID requester;
                try (PreparedStatement find = connection.prepareStatement("""
                        SELECT requester_uuid FROM community_orders
                        WHERE id = ? AND claimer_uuid = ? AND status = 'CLAIMED' AND expires_at > ?
                        """)) {
                    find.setLong(1, orderId);
                    find.setString(2, claimer.toString());
                    find.setLong(3, now);
                    try (ResultSet result = find.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return false;
                        }
                        requester = UUID.fromString(result.getString(1));
                    }
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE community_orders SET status = 'DELIVERED', updated_at = ?
                        WHERE id = ? AND claimer_uuid = ? AND status = 'CLAIMED'
                        """)) {
                    update.setLong(1, now);
                    update.setLong(2, orderId);
                    update.setString(3, claimer.toString());
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                insertMailbox(requester, itemData, now);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public boolean completeOrder(UUID requester, long orderId, long now) throws SQLException {
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                UUID claimer;
                long reward;
                try (PreparedStatement find = connection.prepareStatement("""
                        SELECT claimer_uuid, reward_minor FROM community_orders
                        WHERE id = ? AND requester_uuid = ? AND status = 'DELIVERED'
                        """)) {
                    find.setLong(1, orderId);
                    find.setString(2, requester.toString());
                    try (ResultSet result = find.executeQuery()) {
                        if (!result.next() || result.getString(1) == null) {
                            connection.rollback();
                            return false;
                        }
                        claimer = UUID.fromString(result.getString(1));
                        reward = result.getLong(2);
                    }
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE community_orders SET status = 'COMPLETED', updated_at = ?
                        WHERE id = ? AND requester_uuid = ? AND status = 'DELIVERED'
                        """)) {
                    update.setLong(1, now);
                    update.setLong(2, orderId);
                    update.setString(3, requester.toString());
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                try (PreparedStatement credit = connection.prepareStatement(
                        "UPDATE players SET balance_minor = balance_minor + ? WHERE uuid = ?")) {
                    credit.setLong(1, reward);
                    credit.setString(2, claimer.toString());
                    if (credit.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                insertTransaction(requester.toString(), claimer.toString(), reward,
                        "ORDER_REWARD", "Completed community order #" + orderId, now);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public boolean cancelOrder(UUID requester, long orderId, long now) throws SQLException {
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long reward;
                try (PreparedStatement find = connection.prepareStatement("""
                        SELECT reward_minor FROM community_orders
                        WHERE id = ? AND requester_uuid = ? AND status = 'OPEN'
                        """)) {
                    find.setLong(1, orderId);
                    find.setString(2, requester.toString());
                    try (ResultSet result = find.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return false;
                        }
                        reward = result.getLong(1);
                    }
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE community_orders SET status = 'CANCELLED', updated_at = ?
                        WHERE id = ? AND requester_uuid = ? AND status = 'OPEN'
                        """)) {
                    update.setLong(1, now);
                    update.setLong(2, orderId);
                    update.setString(3, requester.toString());
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                try (PreparedStatement credit = connection.prepareStatement(
                        "UPDATE players SET balance_minor = balance_minor + ? WHERE uuid = ?")) {
                    credit.setLong(1, reward);
                    credit.setString(2, requester.toString());
                    if (credit.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                insertTransaction(null, requester.toString(), reward, "ORDER_REFUND",
                        "Cancelled community order #" + orderId, now);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public int expireOpenOrders(long now) throws SQLException {
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            int expired = 0;
            try {
                List<ExpiredOrder> orders = new ArrayList<>();
                try (PreparedStatement find = connection.prepareStatement("""
                        SELECT id, requester_uuid, reward_minor FROM community_orders
                        WHERE status = 'OPEN' AND expires_at <= ?
                        """)) {
                    find.setLong(1, now);
                    try (ResultSet result = find.executeQuery()) {
                        while (result.next()) {
                            orders.add(new ExpiredOrder(result.getLong(1), UUID.fromString(result.getString(2)),
                                    result.getLong(3)));
                        }
                    }
                }
                for (ExpiredOrder order : orders) {
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE community_orders SET status = 'EXPIRED', updated_at = ?
                            WHERE id = ? AND status = 'OPEN' AND expires_at <= ?
                            """)) {
                        update.setLong(1, now);
                        update.setLong(2, order.id());
                        update.setLong(3, now);
                        if (update.executeUpdate() != 1) {
                            continue;
                        }
                    }
                    try (PreparedStatement refund = connection.prepareStatement(
                            "UPDATE players SET balance_minor = balance_minor + ? WHERE uuid = ?")) {
                        refund.setLong(1, order.rewardMinor());
                        refund.setString(2, order.requester().toString());
                        refund.executeUpdate();
                    }
                    insertTransaction(null, order.requester().toString(), order.rewardMinor(),
                            "ORDER_REFUND", "Expired community order #" + order.id(), now);
                    expired++;
                }
                connection.commit();
                return expired;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public List<MailItem> pendingMail(UUID playerId) throws SQLException {
        synchronized (lock) {
            List<MailItem> mail = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, item_data FROM item_mailbox
                    WHERE player_uuid = ? AND status = 'PENDING' ORDER BY created_at, id
                    """)) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        mail.add(new MailItem(result.getString(1), result.getString(2)));
                    }
                }
            }
            return mail;
        }
    }

    public void queueMailboxItem(UUID playerId, String itemData, long now) throws SQLException {
        synchronized (lock) {
            insertMailbox(playerId, itemData, now);
        }
    }

    public boolean markMailClaimed(String mailId, UUID playerId, long now) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE item_mailbox SET status = 'CLAIMED', claimed_at = ?
                    WHERE id = ? AND player_uuid = ? AND status = 'PENDING'
                    """)) {
                statement.setLong(1, now);
                statement.setString(2, mailId);
                statement.setString(3, playerId.toString());
                return statement.executeUpdate() == 1;
            }
        }
    }

    public String beginSellSession(UUID playerId, long now) throws SQLException {
        String id = UUID.randomUUID().toString();
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sell_sessions(id, player_uuid, status, created_at)
                    VALUES (?, ?, 'OPEN', ?)
                    """)) {
                statement.setString(1, id);
                statement.setString(2, playerId.toString());
                statement.setLong(3, now);
                statement.executeUpdate();
            }
        }
        return id;
    }

    public void addSellItem(String sessionId, UUID playerId, String lineId, String itemData,
                            long valueMinor, long now) throws SQLException {
        if (valueMinor <= 0) {
            throw new IllegalArgumentException("Sell value must be positive.");
        }
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sell_session_items(id, session_id, player_uuid, item_data, value_minor, status)
                    SELECT ?, ?, ?, ?, ?, 'STAGED'
                    WHERE EXISTS (SELECT 1 FROM sell_sessions WHERE id = ? AND player_uuid = ? AND status = 'OPEN')
                    """)) {
                statement.setString(1, lineId);
                statement.setString(2, sessionId);
                statement.setString(3, playerId.toString());
                statement.setString(4, itemData);
                statement.setLong(5, valueMinor);
                statement.setString(6, sessionId);
                statement.setString(7, playerId.toString());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Sell session is no longer open.");
                }
            }
        }
    }

    public List<SellLine> sellLines(String sessionId, UUID playerId) throws SQLException {
        synchronized (lock) {
            List<SellLine> lines = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, item_data, value_minor FROM sell_session_items
                    WHERE session_id = ? AND player_uuid = ? AND status = 'STAGED' ORDER BY rowid
                    """)) {
                statement.setString(1, sessionId);
                statement.setString(2, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        lines.add(new SellLine(result.getString(1), result.getString(2), result.getLong(3)));
                    }
                }
            }
            return lines;
        }
    }

    public long prepareSellCommit(String sessionId, UUID playerId) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE sell_sessions SET status = 'COMMITTING'
                    WHERE id = ? AND player_uuid = ? AND status = 'OPEN'
                    """)) {
                statement.setString(1, sessionId);
                statement.setString(2, playerId.toString());
                if (statement.executeUpdate() != 1) {
                    return -1;
                }
            }
            return sellSessionTotal(sessionId, playerId);
        }
    }

    public boolean finishSellCommit(String sessionId, UUID playerId, long now) throws SQLException {
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long total;
                try (PreparedStatement sum = connection.prepareStatement("""
                        SELECT COALESCE(SUM(value_minor), 0) FROM sell_session_items
                        WHERE session_id = ? AND player_uuid = ? AND status = 'STAGED'
                        """)) {
                    sum.setString(1, sessionId);
                    sum.setString(2, playerId.toString());
                    try (ResultSet result = sum.executeQuery()) {
                        result.next();
                        total = result.getLong(1);
                    }
                }
                if (total <= 0) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE sell_sessions SET status = 'COMPLETED'
                        WHERE id = ? AND player_uuid = ? AND status = 'COMMITTING'
                        """)) {
                    update.setString(1, sessionId);
                    update.setString(2, playerId.toString());
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                try (PreparedStatement credit = connection.prepareStatement(
                        "UPDATE players SET balance_minor = balance_minor + ? WHERE uuid = ?")) {
                    credit.setLong(1, total);
                    credit.setString(2, playerId.toString());
                    if (credit.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                try (PreparedStatement sold = connection.prepareStatement("""
                        UPDATE sell_session_items SET status = 'SOLD'
                        WHERE session_id = ? AND player_uuid = ? AND status = 'STAGED'
                        """)) {
                    sold.setString(1, sessionId);
                    sold.setString(2, playerId.toString());
                    sold.executeUpdate();
                }
                insertTransaction(null, playerId.toString(), total, "ITEM_SALE",
                        "Sell inventory settlement #" + sessionId, now);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public boolean refundSellSession(String sessionId, UUID playerId, long now) throws SQLException {
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement status = connection.prepareStatement("""
                        UPDATE sell_sessions SET status = 'CANCELLED'
                        WHERE id = ? AND player_uuid = ? AND status = 'OPEN'
                        """)) {
                    status.setString(1, sessionId);
                    status.setString(2, playerId.toString());
                    if (status.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                try (PreparedStatement find = connection.prepareStatement("""
                        SELECT id, item_data FROM sell_session_items
                        WHERE session_id = ? AND player_uuid = ? AND status = 'STAGED'
                        """)) {
                    find.setString(1, sessionId);
                    find.setString(2, playerId.toString());
                    try (ResultSet result = find.executeQuery()) {
                        while (result.next()) {
                            insertMailbox(playerId, result.getString(2), now);
                        }
                    }
                }
                try (PreparedStatement refund = connection.prepareStatement("""
                        UPDATE sell_session_items SET status = 'REFUNDED'
                        WHERE session_id = ? AND player_uuid = ? AND status = 'STAGED'
                        """)) {
                    refund.setString(1, sessionId);
                    refund.setString(2, playerId.toString());
                    refund.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public List<String> pendingSellLineIds(UUID playerId) throws SQLException {
        synchronized (lock) {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT i.id FROM sell_session_items i
                    JOIN sell_sessions s ON s.id = i.session_id
                    WHERE i.player_uuid = ? AND i.status = 'STAGED' AND s.status = 'OPEN'
                    """)) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        ids.add(result.getString(1));
                    }
                }
            }
            return ids;
        }
    }

    public void recoverSellSessions(UUID playerId, java.util.Set<String> taggedLineIds, long now)
            throws SQLException {
        synchronized (lock) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<String> committing = new ArrayList<>();
                List<String> open = new ArrayList<>();
                try (PreparedStatement sessions = connection.prepareStatement("""
                        SELECT id, status FROM sell_sessions WHERE player_uuid
                        = ? AND status IN ('OPEN','COMMITTING')
                        """)) {
                    sessions.setString(1, playerId.toString());
                    try (ResultSet result = sessions.executeQuery()) {
                        while (result.next()) {
                            if ("COMMITTING".equals(result.getString(2))) {
                                committing.add(result.getString(1));
                            } else {
                                open.add(result.getString(1));
                            }
                        }
                    }
                }
                for (String sessionId : open) {
                    try (PreparedStatement lines = connection.prepareStatement("""
                            SELECT id, item_data FROM sell_session_items
                            WHERE session_id = ? AND player_uuid = ? AND status = 'STAGED'
                            """)) {
                        lines.setString(1, sessionId);
                        lines.setString(2, playerId.toString());
                        try (ResultSet result = lines.executeQuery()) {
                            while (result.next()) {
                                String lineId = result.getString(1);
                                if (!taggedLineIds.contains(lineId)) {
                                    insertMailbox(playerId, result.getString(2), now);
                                }
                                try (PreparedStatement update = connection.prepareStatement(
                                        "UPDATE sell_session_items SET status = 'REFUNDED' WHERE id = ?")) {
                                    update.setString(1, lineId);
                                    update.executeUpdate();
                                }
                            }
                        }
                    }
                    try (PreparedStatement cancel = connection.prepareStatement(
                            "UPDATE sell_sessions SET status = 'CANCELLED' WHERE id = ? AND status = 'OPEN'")) {
                        cancel.setString(1, sessionId);
                        cancel.executeUpdate();
                    }
                }
                connection.commit();
                for (String sessionId : committing) {
                    connection.setAutoCommit(previousAutoCommit);
                    finishSellCommit(sessionId, playerId, now);
                    connection.setAutoCommit(false);
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private long sellSessionTotal(String sessionId, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(value_minor), 0) FROM sell_session_items
                WHERE session_id = ? AND player_uuid = ? AND status = 'STAGED'
                """)) {
            statement.setString(1, sessionId);
            statement.setString(2, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void insertMailbox(UUID playerId, String itemData, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO item_mailbox(id, player_uuid, item_data, status, created_at)
                VALUES (?, ?, ?, 'PENDING', ?)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, playerId.toString());
            statement.setString(3, itemData);
            statement.setLong(4, now);
            statement.executeUpdate();
        }
    }

    private void insertTransaction(String sender, String receiver, long amount, String type,
                                   String description, long createdAt) throws SQLException {
        try (PreparedStatement log = connection.prepareStatement("""
                INSERT INTO economy_transactions
                (id, sender_uuid, receiver_uuid, amount_minor, transaction_type, description, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            log.setString(1, UUID.randomUUID().toString());
            log.setString(2, sender);
            log.setString(3, receiver);
            log.setLong(4, amount);
            log.setString(5, type);
            log.setString(6, description);
            log.setLong(7, createdAt);
            log.executeUpdate();
        }
    }

    public AdminSnapshot adminSnapshot() throws SQLException {
        synchronized (lock) {
            long players;
            long balance;
            long transactions;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("""
                         SELECT (SELECT COUNT(*) FROM players),
                                COALESCE((SELECT SUM(balance_minor) FROM players), 0),
                                (SELECT COUNT(*) FROM economy_transactions)
                         """)) {
                result.next();
                players = result.getLong(1);
                balance = result.getLong(2);
                transactions = result.getLong(3);
            }
            return new AdminSnapshot(players, balance, transactions);
        }
    }

    public List<TransactionRow> recentTransactions(int limit) throws SQLException {
        synchronized (lock) {
            List<TransactionRow> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT sender_uuid, receiver_uuid, amount_minor, transaction_type, created_at
                    FROM economy_transactions ORDER BY created_at DESC LIMIT ?
                    """)) {
                statement.setInt(1, Math.max(1, Math.min(100, limit)));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(new TransactionRow(result.getString(1), result.getString(2),
                                result.getLong(3), result.getString(4), result.getLong(5)));
                    }
                }
            }
            return rows;
        }
    }

    private Optional<SavedLocation> location(String sql, String... parameters) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setString(i + 1, parameters[i]);
                }
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new SavedLocation(result.getString(1), result.getString(2),
                            result.getDouble(3), result.getDouble(4), result.getDouble(5),
                            result.getFloat(6), result.getFloat(7)));
                }
            }
        }
    }

    private static void bindLocation(PreparedStatement statement, int startIndex, String owner, String name,
                                     Location location, long now) throws SQLException {
        statement.setString(startIndex, owner);
        statement.setString(startIndex + 1, name);
        statement.setString(startIndex + 2, location.getWorld().getName());
        statement.setDouble(startIndex + 3, location.getX());
        statement.setDouble(startIndex + 4, location.getY());
        statement.setDouble(startIndex + 5, location.getZ());
        statement.setFloat(startIndex + 6, location.getYaw());
        statement.setFloat(startIndex + 7, location.getPitch());
        statement.setLong(startIndex + 8, now);
    }

    @Override
    public void close() throws SQLException {
        synchronized (lock) {
            connection.close();
        }
    }

    public record PlayerBalance(String name, long balanceMinor) {
        public String formattedBalance() {
            return Money.format(balanceMinor);
        }
    }

    public record SavedLocation(String name, String world, double x, double y, double z, float yaw, float pitch) {}
    public record PlayerStats(long balanceMinor, long playtimeSeconds, long blocksPlaced,
                              long blocksBroken, long deaths, long lastSeenAt) {}
    public record AdminSnapshot(long playerCount, long totalBalanceMinor, long transactionCount) {}
    public record TransactionRow(String senderUuid, String receiverUuid, long amountMinor,
                                 String type, long createdAt) {}
    public record ModerationEntry(UUID player, String action, String reason, Long expiresAt, long createdAt) {}
    public record LoanSummary(int activeCount, long outstandingMinor, long nearestDueAt) {}
    public record AuctionListing(long id, UUID seller, String itemData, int quantity,
                                 long priceMinor, long expiresAt) {}
    public record AuctionPurchase(UUID seller, long priceMinor, int quantity) {}
    public record CommunityOrder(long id, UUID requester, UUID claimer, String itemKey, int quantity,
                                 long rewardMinor, String status, long expiresAt) {}
    public record MailItem(String id, String itemData) {}
    public record MortgageAccount(String assetKey, long remainingMinor, long nextDueAt,
                                  int installmentsLeft, String status) {}
    public record MortgageSummary(List<MortgageAccount> accounts, long outstandingMinor, long nearestDueAt) {}
    public record MortgagePayment(String assetKey, long paidMinor, long remainingMinor,
                                 long nextDueAt, int installmentsLeft) {}
    public record SellLine(String id, String itemData, long valueMinor) {}
    private record ExpiredOrder(long id, UUID requester, long rewardMinor) {}
    private record ExpiredAuction(long id, UUID seller, String itemData) {}
}