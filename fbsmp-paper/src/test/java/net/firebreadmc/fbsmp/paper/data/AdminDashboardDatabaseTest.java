package net.firebreadmc.fbsmp.paper.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for the admin dashboard tables and data-layer methods
 * introduced with the V5 migration: operator accounts, global settings,
 * announcements, scheduled tasks, console logging, directory search and the
 * audited player wipe.
 */
class AdminDashboardDatabaseTest {
    @TempDir
    Path directory;

    private FbsmpDatabase database;
    private UUID alice;
    private UUID bob;
    private long now;

    @BeforeEach
    void openDatabase() throws Exception {
        database = new FbsmpDatabase(directory.resolve("fbsmp-admin-test.sqlite"), getClass().getClassLoader());
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        now = 1_700_000_000_000L;
        database.ensurePlayer(alice, "Alice", now, 5_000);
        database.ensurePlayer(bob, "Bob", now, 12_500);
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        database.close();
    }

    @Test
    void v5SchemaIsAppliedAndIdempotentAcrossReopen() throws Exception {
        assertEquals(5, database.schemaVersion());
        database.close();
        database = new FbsmpDatabase(directory.resolve("fbsmp-admin-test.sqlite"), getClass().getClassLoader());
        assertEquals(5, database.schemaVersion());
        assertEquals(2, database.playerDirectory("", 50).size());
    }

    @Test
    void adminAccountsRoundTripIncludingRoleAndPasswordChanges() throws Exception {
        long id = database.createAdminUser("owner", "hash1", "salt1", 210_000, "OWNER", now);
        assertTrue(id > 0);
        assertEquals(1, database.countAdminUsers());

        FbsmpDatabase.AdminUserRow created = database.adminUser("OWNER").orElseThrow();
        assertEquals(id, created.id());
        assertEquals("owner", created.username());
        assertEquals("OWNER", created.role());
        assertEquals("hash1", created.passwordHash());
        assertNull(created.lastLoginAt());

        database.markAdminLogin(id, now + 1);
        assertEquals(now + 1, database.adminUser("owner").orElseThrow().lastLoginAt());

        assertTrue(database.updateAdminUser(id, "ADMINISTRATOR", null, null, 0, now + 2));
        assertEquals("ADMINISTRATOR", database.adminUser("owner").orElseThrow().role());

        assertTrue(database.updateAdminUser(id, "ADMINISTRATOR", "hash2", "salt2", 210_000, now + 3));
        FbsmpDatabase.AdminUserRow updated = database.adminUser("owner").orElseThrow();
        assertEquals("hash2", updated.passwordHash());
        assertEquals("salt2", updated.passwordSalt());

        assertTrue(database.deleteAdminUser(id));
        assertEquals(0, database.countAdminUsers());
    }

    @Test
    void globalSettingsAreStoredAndReadBack() throws Exception {
        assertTrue(database.allSettings().isEmpty());
        assertEquals("Asia/Kolkata", database.globalSetting("timezone", "Asia/Kolkata"));
        database.setGlobalSetting("timezone", "UTC", now);
        database.setGlobalSetting("maintenance", "on", now);
        database.setGlobalSetting("timezone", "Europe/London", now);
        assertEquals("Europe/London", database.globalSetting("timezone", "fallback"));
        assertEquals("on", database.globalSetting("maintenance", "off"));
        assertEquals(2, database.allSettings().size());
    }

    @Test
    void announcementsAreQueuedOnceAndMarkedDelivered() throws Exception {
        long id = database.createAnnouncement("Restart soon", "owner", now);
        List<FbsmpDatabase.AnnouncementRow> pending = database.pendingAnnouncements();
        assertEquals(1, pending.size());
        assertEquals("Restart soon", pending.get(0).message());
        assertEquals("owner", pending.get(0).actorName());

        database.markAnnouncementDelivered(id, now + 5);
        assertTrue(database.pendingAnnouncements().isEmpty());
    }

    @Test
    void scheduledTasksRespectEnablementAndDueTime() throws Exception {
        long id = database.createScheduledTask("ANNOUNCE", "Be nice", 60, now - 1, now);
        assertEquals(1, database.scheduledTasks().size());
        assertEquals(1, database.dueScheduledTasks(now).size());
        assertTrue(database.dueScheduledTasks(now - 10_000).isEmpty());

        database.markScheduledTaskRun(id, now + 3_600_000L, now);
        FbsmpDatabase.ScheduledTaskRow ran = database.scheduledTasks().get(0);
        assertEquals(1, ran.runCount());
        assertEquals(now, ran.lastRunAt());
        assertTrue(database.dueScheduledTasks(now).isEmpty());

        assertTrue(database.setScheduledTaskEnabled(id, false));
        assertFalse(database.scheduledTasks().get(0).enabled());
        assertTrue(database.dueScheduledTasks(now + 10_000_000L).isEmpty());

        assertTrue(database.deleteScheduledTask(id));
        assertTrue(database.scheduledTasks().isEmpty());
    }

    @Test
    void playerDirectorySearchAndEconomyReportUseRealBalances() throws Exception {
        List<FbsmpDatabase.PlayerRow> all = database.playerDirectory("", 50);
        assertEquals(2, all.size());
        assertEquals("Alice", all.get(0).name(), "equal activity breaks ties alphabetically");

        assertEquals(1, database.playerDirectory("ali", 50).size());
        assertEquals("Alice", database.playerByName("alice").orElseThrow().name());
        assertTrue(database.playerByName("nobody").isEmpty());

        FbsmpDatabase.EconomyReport report = database.economyReport();
        assertEquals(17_500, report.inCirculationMinor());

        List<FbsmpDatabase.PlayerBalance> filtered = database.topBalances("bo", 50);
        assertEquals(1, filtered.size());
        assertEquals("Bob", filtered.get(0).name());
        assertEquals(12_500, filtered.get(0).balanceMinor());
    }

    @Test
    void consoleLogAndAuditTrailAreQueryable() throws Exception {
        database.logConsoleCommand("owner", "say hello", now);
        database.logConsoleCommand("owner", "time set day", now + 1);
        List<FbsmpDatabase.ConsoleLogRow> log = database.recentConsoleCommands(10);
        assertEquals(2, log.size());
        assertEquals("time set day", log.get(0).command());

        database.writeAudit(UUID.randomUUID(), "owner", "ECONOMY_GIVE", "Alice", "0.00", "10.00", "", now);
        List<FbsmpDatabase.AuditRow> audit = database.recentAuditLog(10);
        assertEquals(1, audit.size());
        assertEquals("ECONOMY_GIVE", audit.get(0).action());
        assertEquals("Alice", audit.get(0).target());
    }

    @Test
    void clearingModerationMakesAnActiveBanInactive() throws Exception {
        database.recordModeration(alice, bob, "BAN", "griefing", null, now);
        assertTrue(database.activeModeration(alice, "BAN", now).isPresent());

        assertEquals(1, database.clearActiveModeration(alice, "BAN", now + 1));
        assertTrue(database.activeModeration(alice, "BAN", now + 2).isEmpty());
        assertEquals(1, database.moderationFor(alice, 10).size(), "history is preserved");
    }

    @Test
    void wipePlayerRemovesProfileAndLedgerButKeepsOthers() throws Exception {
        database.credit(alice, 1_000, "ADMIN_GRANT", "test", now);
        database.recordModeration(alice, bob, "BAN", "test", null, now);

        database.wipePlayer(alice);

        assertTrue(database.playerByName("Alice").isEmpty());
        assertEquals(1, database.playerDirectory("", 50).size());
        assertTrue(database.transactionsFor(alice, 50).isEmpty());
        assertTrue(database.moderationFor(alice, 10).isEmpty());
        assertNotNull(database.playerByName("Bob").orElse(null));
    }
}
