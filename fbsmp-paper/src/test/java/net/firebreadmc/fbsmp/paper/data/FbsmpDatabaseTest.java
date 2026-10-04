package net.firebreadmc.fbsmp.paper.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FbsmpDatabaseTest {
    @TempDir
    Path directory;

    private FbsmpDatabase database;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void openDatabase() throws Exception {
        database = new FbsmpDatabase(directory.resolve("fbsmp-test.sqlite"), getClass().getClassLoader());
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        database.ensurePlayer(alice, "Alice", 1_700_000_000_000L, 100_000);
        database.ensurePlayer(bob, "Bob", 1_700_000_000_000L, 100_000);
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        database.close();
    }

    @Test
    void migrationsAndGiftTransfersArePersistentAndAtomic() throws Exception {
        assertTrue(database.transfer(alice, "Alice", bob, "Bob", 12_345,
                "GIFT", "Confirmed gift", 1_700_000_001_000L));
        assertEquals(87_655, database.balance(alice));
        assertEquals(112_345, database.balance(bob));

        assertFalse(database.transfer(alice, "Alice", bob, "Bob", 1_000_000,
                "GIFT", "Confirmed gift", 1_700_000_002_000L));
        assertEquals(87_655, database.balance(alice));
        assertEquals(112_345, database.balance(bob));
    }

    @Test
    void loansMortgagePaymentsAndAuctionPurchasesUseDatabaseTransactions() throws Exception {
        long now = 1_700_000_000_000L;
        assertTrue(database.issueLoan(alice, 20_000, 1_000, 50_000, now + 60_000, now));
        assertEquals(21_000, database.loanSummary(alice).outstandingMinor());
        assertEquals(120_000, database.balance(alice));
        assertEquals(21_000, database.repayLoans(alice, 21_000, now + 1));
        assertEquals(0, database.loanSummary(alice).outstandingMinor());

        assertTrue(database.createMortgage(alice, "north-plot", 50_000, 10_000,
                2_000, 4, 60_000, now));
        assertEquals(42_000, database.mortgageSummary(alice).outstandingMinor());
        FbsmpDatabase.MortgagePayment firstPayment =
                database.payMortgageInstallment(alice, now + 60_000, 60_000);
        assertNotNull(firstPayment);
        assertTrue(firstPayment.paidMinor() > 0);
        assertEquals(31_500, firstPayment.remainingMinor());
        assertFalse(database.createMortgage(bob, "north-plot", 50_000, 10_000,
                2_000, 4, 60_000, now));

        String itemData = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        long listing = database.createAuction(alice, itemData, 3, 5_000, now + 360_000, now);
        assertTrue(listing > 0);
        FbsmpDatabase.AuctionPurchase purchase =
                database.purchaseAuction(bob, listing, now + 1);
        assertNotNull(purchase);
        assertEquals(5_000, purchase.priceMinor());
        assertEquals(95_000, database.balance(bob));
        assertEquals(83_500, database.balance(alice));
        assertEquals(itemData, database.pendingMail(bob).get(0).itemData());
    }

    @Test
    void communityOrderEscrowPaysOnlyAfterAcceptedDelivery() throws Exception {
        long now = 1_700_000_000_000L;
        assertTrue(database.createOrder(alice, "BREAD", 4, 2_500, now + 360_000, now));
        assertEquals(97_500, database.balance(alice));
        FbsmpDatabase.CommunityOrder order = database.openOrders(now, 10).get(0);
        assertTrue(database.claimOrder(bob, order.id(), now + 1));
        String itemData = Base64.getEncoder().encodeToString(new byte[]{4, 5, 6});
        assertTrue(database.deliverOrder(bob, order.id(), itemData, now + 2));
        assertEquals(100_000, database.balance(bob));
        assertEquals(itemData, database.pendingMail(alice).get(0).itemData());
        assertTrue(database.completeOrder(alice, order.id(), now + 3));
        assertEquals(102_500, database.balance(bob));
        assertEquals(97_500, database.balance(alice));
        assertFalse(database.completeOrder(alice, order.id(), now + 4));
    }

    @Test
    void sellEscrowCommitsOrRefundsWithoutLosingTheSerializedItem() throws Exception {
        String itemData = Base64.getEncoder().encodeToString(new byte[]{7, 8, 9});
        String completed = database.beginSellSession(alice, 1_700_000_000_000L);
        database.addSellItem(completed, alice, UUID.randomUUID().toString(), itemData, 700, 1_700_000_000_001L);
        assertEquals(700, database.prepareSellCommit(completed, alice));
        assertTrue(database.finishSellCommit(completed, alice, 1_700_000_000_002L));
        assertEquals(100_700, database.balance(alice));

        String cancelled = database.beginSellSession(alice, 1_700_000_000_003L);
        database.addSellItem(cancelled, alice, UUID.randomUUID().toString(), itemData, 100, 1_700_000_000_004L);
        assertTrue(database.refundSellSession(cancelled, alice, 1_700_000_000_005L));
        assertEquals(100_700, database.balance(alice));
        assertTrue(database.pendingMail(alice).stream().anyMatch(mail -> mail.itemData().equals(itemData)));
    }
}