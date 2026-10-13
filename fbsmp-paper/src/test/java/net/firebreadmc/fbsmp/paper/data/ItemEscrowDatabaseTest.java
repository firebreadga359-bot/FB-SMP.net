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

class ItemEscrowDatabaseTest {
    @TempDir
    Path directory;

    private FbsmpDatabase database;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void openDatabase() throws Exception {
        database = new FbsmpDatabase(directory.resolve("fbsmp-escrow.sqlite"), getClass().getClassLoader());
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        database.ensurePlayer(alice, "Alice", 1_700_000_000_000L, 0);
        database.ensurePlayer(bob, "Bob", 1_700_000_000_000L, 0);
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        database.close();
    }

    @Test
    void itemEscrowMigrationIsCurrent() throws Exception {
        assertEquals(6, database.schemaVersion());
    }

    @Test
    void unconfirmedEscrowIsReturnedToTheOwnerOnRecovery() throws Exception {
        String itemData = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        database.createItemEscrow(alice, itemData, "GIFT", 1_700_000_000_001L);
        assertEquals(1, database.pendingItemEscrowCount());

        assertEquals(1, database.recoverItemEscrow(1_700_000_000_002L));
        assertEquals(0, database.pendingItemEscrowCount());
        assertTrue(database.pendingMail(alice).stream().anyMatch(mail -> mail.itemData().equals(itemData)));
        assertTrue(database.pendingMail(bob).isEmpty());
    }

    @Test
    void deliveringEscrowMovesTheItemAtomicallyAndIsIdempotent() throws Exception {
        String itemData = Base64.getEncoder().encodeToString(new byte[]{4, 5, 6});
        String escrowId = database.createItemEscrow(alice, itemData, "GIFT", 1_700_000_000_001L);

        assertTrue(database.deliverEscrowTo(escrowId, bob, 1_700_000_000_002L));
        assertEquals(0, database.pendingItemEscrowCount());
        assertTrue(database.pendingMail(bob).stream().anyMatch(mail -> mail.itemData().equals(itemData)));
        // A second delivery cannot duplicate the item: the escrow row is gone.
        assertFalse(database.deliverEscrowTo(escrowId, bob, 1_700_000_000_003L));
        assertEquals(1, database.pendingMail(bob).size());
    }

    @Test
    void auctionListingClearsItsEscrowInTheSameTransaction() throws Exception {
        String itemData = Base64.getEncoder().encodeToString(new byte[]{7, 8, 9});
        String escrowId = database.createItemEscrow(alice, itemData, "AUCTION", 1_700_000_000_001L);

        long listing = database.createAuction(alice, itemData, 3, 5_000, 1_700_000_360_000L,
                1_700_000_000_002L, escrowId);
        assertTrue(listing > 0);
        assertEquals(0, database.pendingItemEscrowCount());
        assertTrue(database.pendingMail(alice).isEmpty());
        assertEquals(1, database.openAuctions(1_700_000_000_003L, 10).size());
    }

    @Test
    void rejectedListingKeepsTheEscrowSoNothingIsLost() throws Exception {
        String itemData = Base64.getEncoder().encodeToString(new byte[]{10, 11, 12});
        long now = 1_700_000_000_000L;
        for (int i = 0; i < 10; i++) {
            assertTrue(database.createAuction(alice, itemData, 1, 5_000, now + 360_000, now) > 0);
        }
        String escrowId = database.createItemEscrow(alice, itemData, "AUCTION", now + 1);
        long rejected = database.createAuction(alice, itemData, 1, 5_000, now + 360_000, now + 2, escrowId);
        assertEquals(-1, rejected);
        assertEquals(1, database.pendingItemEscrowCount());
    }
}
