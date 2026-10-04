CREATE TABLE IF NOT EXISTS item_mailbox (
    id TEXT PRIMARY KEY,
    player_uuid TEXT NOT NULL,
    item_data TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('PENDING','CLAIMED')),
    created_at INTEGER NOT NULL,
    claimed_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_mailbox_pending ON item_mailbox(player_uuid, status, created_at);
CREATE TABLE IF NOT EXISTS property_ownership (
    asset_key TEXT PRIMARY KEY COLLATE NOCASE,
    owner_uuid TEXT NOT NULL,
    mortgage_id INTEGER,
    acquired_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_loans_active ON loans(player_uuid, status, due_at);
CREATE INDEX IF NOT EXISTS idx_orders_open ON community_orders(status, expires_at);
CREATE INDEX IF NOT EXISTS idx_mortgages_active ON mortgages(player_uuid, status, next_due_at);