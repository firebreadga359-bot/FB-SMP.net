package net.firebreadmc.fbsmp.paper.data;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the real {@link FbsmpDatabase} migration runner against a copy of a live
 * pre-V4 database, proving V4 applies to a legacy schema without data loss.
 *
 * <p>The fixture is a WAL-safe snapshot taken before the upgrade. It is optional:
 * when absent (e.g. on a clean checkout) the test is skipped rather than failing.
 */
class LiveDbMigrationTest {

    private static final UUID LEGACY_PLAYER = UUID.fromString("00000000-0000-0000-0009-01f3f76a2a56");

    @TempDir
    Path directory;

    private static Path fixture() {
        String override = System.getProperty("fbsmp.live.db.fixture");
        if (override != null) {
            return Paths.get(override);
        }
        Path root = Paths.get("_staging/fb-smp-upgrade/20261011-115802/db-backups/fbsmp-before-v4.sqlite");
        if (Files.isRegularFile(root)) {
            return root;
        }
        Path fromModule = Paths.get("..").resolve(root);
        return Files.isRegularFile(fromModule) ? fromModule : root;
    }

    @Test
    void v4AppliesToLegacyLiveDatabaseWithoutDataLoss() throws Exception {
        Path source = fixture();
        Assumptions.assumeTrue(Files.isRegularFile(source),
                "live-DB backup fixture not present; skipping");

        Path copy = directory.resolve("legacy-live.sqlite");
        Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + copy)) {
            assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));
            assertEquals(3, count(connection, "SELECT count(*) FROM schema_migrations"));
            assertFalse(hasColumn(connection, "loans", "issue_day_index"), "pre-state must be pre-V4");
            assertFalse(tableExists(connection, "loan_timeouts"), "pre-state must be pre-V4");
            assertEquals(1, count(connection, "SELECT count(*) FROM players"));
            assertEquals(1, count(connection, "SELECT count(*) FROM loans"));
        }

        FbsmpDatabase database = new FbsmpDatabase(copy, getClass().getClassLoader());
        database.close();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + copy)) {
            assertEquals(5, count(connection, "SELECT count(*) FROM schema_migrations"));
            assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));

            assertTrue(hasColumn(connection, "loans", "issue_day_index"));
            assertTrue(hasColumn(connection, "loans", "term_days"));
            assertTrue(hasColumn(connection, "loans", "original_remaining_minor"));
            for (String table : new String[]{"loan_timeouts", "loan_events", "loan_notices",
                    "loan_meta", "fbsmp_audit_log", "admin_users", "dashboard_settings",
                    "admin_console_log", "scheduled_tasks", "announcements"}) {
                assertTrue(tableExists(connection, table), "missing migration table " + table);
            }

            assertEquals(1, count(connection, "SELECT count(*) FROM players"),
                    "player rows must be preserved");
            assertEquals(1, count(connection, "SELECT count(*) FROM loans"),
                    "loan rows must be preserved");
            try (ResultSet result = connection.createStatement().executeQuery(
                    "SELECT status FROM loans WHERE id = 1")) {
                assertTrue(result.next(), "legacy loan must survive migration");
                assertEquals("PAID", result.getString(1));
            }
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM players WHERE uuid = '" + LEGACY_PLAYER + "'"));
        }
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }
}
