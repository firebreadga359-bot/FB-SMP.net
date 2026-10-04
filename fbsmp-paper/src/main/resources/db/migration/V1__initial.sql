CREATE TABLE IF NOT EXISTS schema_migrations (
    version INTEGER PRIMARY KEY,
    applied_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS players (
    uuid TEXT PRIMARY KEY,
    last_name TEXT NOT NULL,
    balance_minor INTEGER NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
    first_join_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    playtime_seconds INTEGER NOT NULL DEFAULT 0,
    blocks_placed INTEGER NOT NULL DEFAULT 0,
    blocks_broken INTEGER NOT NULL DEFAULT 0,
    deaths INTEGER NOT NULL DEFAULT 0,
    starter_claimed INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS economy_transactions (
    id TEXT PRIMARY KEY,
    sender_uuid TEXT,
    receiver_uuid TEXT NOT NULL,
    amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
    transaction_type TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_transactions_created ON economy_transactions(created_at DESC);
CREATE TABLE IF NOT EXISTS homes (
    player_uuid TEXT NOT NULL,
    name TEXT NOT NULL COLLATE NOCASE,
    world TEXT NOT NULL,
    x REAL NOT NULL,
    y REAL NOT NULL,
    z REAL NOT NULL,
    yaw REAL NOT NULL,
    pitch REAL NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY(player_uuid, name)
);
CREATE TABLE IF NOT EXISTS warps (
    name TEXT PRIMARY KEY COLLATE NOCASE,
    world TEXT NOT NULL,
    x REAL NOT NULL,
    y REAL NOT NULL,
    z REAL NOT NULL,
    yaw REAL NOT NULL,
    pitch REAL NOT NULL,
    created_by TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS settings (
    player_uuid TEXT NOT NULL,
    setting TEXT NOT NULL,
    value TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY(player_uuid, setting)
);
CREATE TABLE IF NOT EXISTS ignored_players (
    player_uuid TEXT NOT NULL,
    ignored_uuid TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY(player_uuid, ignored_uuid)
);
CREATE TABLE IF NOT EXISTS auctions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    seller_uuid TEXT NOT NULL,
    item_data TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK(quantity > 0),
    price_minor INTEGER NOT NULL CHECK(price_minor > 0),
    status TEXT NOT NULL CHECK(status IN ('OPEN','SOLD','CANCELLED','EXPIRED')),
    expires_at INTEGER NOT NULL,
    buyer_uuid TEXT,
    created_at INTEGER NOT NULL,
    completed_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_auctions_open ON auctions(status, expires_at);
CREATE TABLE IF NOT EXISTS loans (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT NOT NULL,
    principal_minor INTEGER NOT NULL,
    interest_minor INTEGER NOT NULL,
    remaining_minor INTEGER NOT NULL,
    due_at INTEGER NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('ACTIVE','PAID','OVERDUE','DEFAULTED')),
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS mortgages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT NOT NULL,
    asset_key TEXT NOT NULL,
    principal_minor INTEGER NOT NULL,
    remaining_minor INTEGER NOT NULL,
    installment_minor INTEGER NOT NULL,
    installments_left INTEGER NOT NULL,
    next_due_at INTEGER NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('ACTIVE','PAID','OVERDUE','DEFAULTED')),
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS community_orders (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    requester_uuid TEXT NOT NULL,
    item_key TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK(quantity > 0),
    reward_minor INTEGER NOT NULL CHECK(reward_minor > 0),
    status TEXT NOT NULL CHECK(status IN ('OPEN','CLAIMED','DELIVERED','COMPLETED','CANCELLED','EXPIRED')),
    claimer_uuid TEXT,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS moderation_records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT NOT NULL,
    staff_uuid TEXT NOT NULL,
    action TEXT NOT NULL,
    reason TEXT NOT NULL,
    expires_at INTEGER,
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS calendar_state (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);