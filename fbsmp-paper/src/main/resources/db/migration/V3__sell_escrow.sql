CREATE TABLE IF NOT EXISTS sell_sessions (
    id TEXT PRIMARY KEY,
    player_uuid TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('OPEN','COMMITTING','COMPLETED','CANCELLED')),
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sell_sessions_pending ON sell_sessions(player_uuid, status);
CREATE TABLE IF NOT EXISTS sell_session_items (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    player_uuid TEXT NOT NULL,
    item_data TEXT NOT NULL,
    value_minor INTEGER NOT NULL CHECK(value_minor > 0),
    status TEXT NOT NULL CHECK(status IN ('STAGED','SOLD','REFUNDED'))
);
CREATE INDEX IF NOT EXISTS idx_sell_items_session ON sell_session_items(session_id, status);