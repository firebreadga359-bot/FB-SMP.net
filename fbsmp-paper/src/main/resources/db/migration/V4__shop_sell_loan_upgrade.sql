-- Shop / Sell / Loan upgrade: additive schema only.
-- No existing table is rewritten and no existing column is dropped, so the
-- migration is safe to apply while the old plugin jar is still installed.

-- Loan v2: calendar-based due dates, warnings, overdue events and timeouts.
ALTER TABLE loans ADD COLUMN issue_day_index INTEGER;
ALTER TABLE loans ADD COLUMN due_day_index INTEGER;
ALTER TABLE loans ADD COLUMN term_days INTEGER;
ALTER TABLE loans ADD COLUMN original_remaining_minor INTEGER;
ALTER TABLE loans ADD COLUMN warning_state TEXT NOT NULL DEFAULT 'NONE';
ALTER TABLE loans ADD COLUMN warning_sent_for_day INTEGER;
ALTER TABLE loans ADD COLUMN overdue_event_id INTEGER;
ALTER TABLE loans ADD COLUMN forgiven INTEGER NOT NULL DEFAULT 0;
ALTER TABLE loans ADD COLUMN settled INTEGER NOT NULL DEFAULT 0;
ALTER TABLE loans ADD COLUMN created_calendar_date TEXT NOT NULL DEFAULT '';

CREATE TABLE IF NOT EXISTS loan_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    loan_id INTEGER NOT NULL,
    player_uuid TEXT NOT NULL,
    event_type TEXT NOT NULL,
    day_index INTEGER,
    detail TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_loan_events_loan ON loan_events(loan_id, event_type);

CREATE TABLE IF NOT EXISTS loan_timeouts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    loan_id INTEGER NOT NULL,
    player_uuid TEXT NOT NULL,
    overdue_event_id INTEGER NOT NULL,
    reason TEXT NOT NULL DEFAULT '',
    remaining_minor INTEGER NOT NULL,
    started_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    released INTEGER NOT NULL DEFAULT 0,
    released_at INTEGER,
    UNIQUE(loan_id, overdue_event_id)
);
CREATE INDEX IF NOT EXISTS idx_loan_timeouts_active ON loan_timeouts(player_uuid, released, expires_at);

CREATE TABLE IF NOT EXISTS loan_meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Player-facing loan notices (advance warning, overdue). Persisted so an offline
-- player receives each notice exactly once, on their next join.
CREATE TABLE IF NOT EXISTS loan_notices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT NOT NULL,
    loan_id INTEGER,
    notice_type TEXT NOT NULL,
    message TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    delivered_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_notices_undelivered ON loan_notices(player_uuid, delivered_at);

-- Append-only audit log for every action that touches money, debt, prices,
-- timeouts or availability.
CREATE TABLE IF NOT EXISTS fbsmp_audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    actor_uuid TEXT,
    actor_name TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT NOT NULL DEFAULT '',
    before_json TEXT NOT NULL DEFAULT '',
    after_json TEXT NOT NULL DEFAULT '',
    reason TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_created ON fbsmp_audit_log(created_at DESC);
