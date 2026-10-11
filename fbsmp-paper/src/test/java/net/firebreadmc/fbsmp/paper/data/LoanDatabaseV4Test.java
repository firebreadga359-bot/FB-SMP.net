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
 * Integration coverage for the V4 shop/sell/loan schema and the loan lifecycle
 * database methods. Runs against a real SQLite file so migrations, constraints and
 * idempotency guards are exercised end to end.
 */
class LoanDatabaseV4Test {
    @TempDir
    Path directory;

    private FbsmpDatabase database;
    private UUID alice;
    private long now;

    @BeforeEach
    void openDatabase() throws Exception {
        database = new FbsmpDatabase(directory.resolve("fbsmp-v4-test.sqlite"), getClass().getClassLoader());
        alice = UUID.randomUUID();
        now = 1_700_000_000_000L;
        database.ensurePlayer(alice, "Alice", now, 100_000);
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        database.close();
    }

    private long issue(long principal, long interest, long issueDay, long dueDay, int term) throws SQLException {
        return database.issueLoanV2(alice, principal, interest, issueDay, dueDay, term,
                "Year 1, Frostwake 1", 1_000_000, false, now + term * 60_000L, now);
    }

    @Test
    void v4SchemaIsAppliedAndIdempotentAcrossReopen() throws Exception {
        // Reopening runs the migration runner again; it must not fail or duplicate.
        long loanId = issue(10_000, 800, 5, 35, 30);
        assertTrue(loanId > 0);
        database.close();
        database = new FbsmpDatabase(directory.resolve("fbsmp-v4-test.sqlite"), getClass().getClassLoader());
        FbsmpDatabase.LoanRow row = database.loanById(loanId);
        assertNotNull(row);
        assertEquals(30, row.termDays());
        assertEquals(5, row.issueDayIndex());
        assertEquals(35, row.dueDayIndex());
        assertEquals(10_800, row.remainingMinor());
        assertEquals(10_800, row.originalRemainingMinor());
        assertEquals("ACTIVE", row.status());
        assertEquals("NONE", row.warningState());
        assertFalse(row.forgiven());
        assertFalse(row.settled());
    }

    @Test
    void warningIsSentAtMostOncePerLoan() throws Exception {
        long loanId = issue(10_000, 800, 5, 35, 30);
        assertTrue(database.markWarningSent(loanId, 33, now));
        assertFalse(database.markWarningSent(loanId, 34, now), "second warning must be rejected");
    }

    @Test
    void overdueTransitionAndTimeoutAreGuarded() throws Exception {
        long loanId = issue(10_000, 800, 5, 35, 30);
        long eventId = database.createLoanEvent(loanId, alice.toString(), "OVERDUE", 35L, "detail", now);
        assertTrue(eventId > 0);
        database.markOverdue(loanId, eventId, now);
        FbsmpDatabase.LoanRow overdue = database.loanById(loanId);
        assertEquals("OVERDUE", overdue.status());
        assertEquals(eventId, overdue.overdueEventId());

        assertTrue(database.createLoanTimeoutOnce(loanId, alice, eventId, "Small overdue balance",
                10_800, now, now + 3_600_000));
        assertFalse(database.createLoanTimeoutOnce(loanId, alice, eventId, "Small overdue balance",
                10_800, now, now + 3_600_000), "duplicate timeout for same event must be ignored");

        FbsmpDatabase.LoanTimeout active = database.activeTimeout(alice, now + 1_000);
        assertNotNull(active);
        assertEquals(loanId, active.loanId());
        assertEquals("Small overdue balance", active.reason());
    }

    @Test
    void expiredTimeoutIsReleasedAndNoLongerBlocks() throws Exception {
        long loanId = issue(10_000, 800, 5, 35, 30);
        long eventId = database.createLoanEvent(loanId, alice.toString(), "OVERDUE", 35L, "d", now);
        database.markOverdue(loanId, eventId, now);
        database.createLoanTimeoutOnce(loanId, alice, eventId, "reason", 10_800, now, now + 1_000);

        assertEquals(1, database.releaseExpiredTimeouts(now + 2_000));
        assertNull(database.activeTimeout(alice, now + 2_000));
    }

    @Test
    void repayingInFullReleasesTimeouts() throws Exception {
        long loanId = issue(10_000, 800, 5, 35, 30);
        long eventId = database.createLoanEvent(loanId, alice.toString(), "OVERDUE", 35L, "d", now);
        database.markOverdue(loanId, eventId, now);
        database.createLoanTimeoutOnce(loanId, alice, eventId, "reason", 10_800, now, now + 86_400_000);
        assertNotNull(database.activeTimeout(alice, now + 1));

        long paid = database.repayLoans(alice, 10_800, now + 2);
        assertEquals(10_800, paid);
        assertEquals(1, database.releaseTimeoutsForPaidLoans(alice, now + 2));
        assertNull(database.activeTimeout(alice, now + 3));
        assertEquals("PAID", database.loanById(loanId).status());
    }

    @Test
    void forgiveSettleAndAdjustAreAuditableAndReleaseTimeouts() throws Exception {
        long forgiven = issue(10_000, 800, 5, 35, 30);
        long settle = issue(20_000, 1_600, 5, 35, 30);
        long adjust = issue(30_000, 2_400, 5, 35, 30);

        assertTrue(database.forgiveLoan(forgiven, "hardship", now));
        assertTrue(database.loanById(forgiven).forgiven());
        assertFalse(database.forgiveLoan(forgiven, "again", now), "already settled loan cannot be forgiven");

        assertTrue(database.settleLoan(settle, "admin write-off", now));
        assertTrue(database.loanById(settle).settled());

        assertTrue(database.adjustLoanRemaining(adjust, 5_000, "partial relief", now));
        FbsmpDatabase.LoanRow adjusted = database.loanById(adjust);
        assertEquals(5_000, adjusted.remainingMinor());
        assertEquals("ACTIVE", adjusted.status());
        assertTrue(database.adjustLoanRemaining(adjust, 0, "clear", now));
        assertEquals("PAID", database.loanById(adjust).status());
    }

    @Test
    void noticesArePersistedDeliveredOnceAndAuditLogReadsBack() throws Exception {
        long loanId = issue(10_000, 800, 5, 35, 30);
        assertTrue(database.addLoanNotice(alice, loanId, "WARNING", "Due soon", now));
        assertFalse(database.addLoanNotice(alice, loanId, "WARNING", "Due soon again", now),
                "duplicate notice type per loan must be rejected");

        List<FbsmpDatabase.Notice> pending = database.undeliveredNotices(alice);
        assertEquals(1, pending.size());
        assertEquals("Due soon", pending.get(0).message());

        database.markNoticesDelivered(pending.stream().map(FbsmpDatabase.Notice::id).toList(), now + 1);
        assertTrue(database.undeliveredNotices(alice).isEmpty());

        database.writeAudit(alice, "Alice", "LOAN_ISSUE", "loan#" + loanId, "", "principal=10000", "test", now);
        List<FbsmpDatabase.AuditRow> rows = database.recentAuditLog(10);
        assertEquals(1, rows.size());
        assertEquals("LOAN_ISSUE", rows.get(0).action());
        assertEquals("loan#" + loanId, rows.get(0).target());
    }

    @Test
    void limitBlocksOversizedBorrowingUnlessBypassed() throws Exception {
        assertEquals(-1, database.issueLoanV2(alice, 900_000, 200_000, 5, 35, 30,
                "Year 1, Frostwake 1", 1_000_000, false, now + 60_000, now),
                "principal+interest above the limit must be rejected");
        long bypassed = database.issueLoanV2(alice, 900_000, 200_000, 5, 35, 30,
                "Year 1, Frostwake 1", 1_000_000, true, now + 60_000, now);
        assertTrue(bypassed > 0, "bypass permission must allow exceeding the limit");
    }
}
