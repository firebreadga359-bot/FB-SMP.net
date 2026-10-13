-- Crash-safe item escrow. Additive only, no existing table is rewritten.
--
-- Item transfers that remove an item from a player inventory (gifts, auction
-- listings) first record the serialized item here, then delete the row once the
-- destination is durable. A crash between the two leaves a PENDING row that
-- startup recovery returns to the source player's mailbox, so no item is lost.

CREATE TABLE IF NOT EXISTS item_escrow (
    id TEXT PRIMARY KEY,
    player_uuid TEXT NOT NULL,
    item_data TEXT NOT NULL,
    escrow_type TEXT NOT NULL CHECK(escrow_type IN ('GIFT','AUCTION')),
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_item_escrow_player ON item_escrow(player_uuid, created_at);
