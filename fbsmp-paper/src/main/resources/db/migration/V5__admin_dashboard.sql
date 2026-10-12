-- FB-SMP Ultimate Admin Control Panel dashboard-owned tables.
-- Additive only. Existing gameplay tables are untouched.

-- Dashboard operator accounts. Passwords are stored as PBKDF2-HMAC-SHA256
-- (salt + iterations + hash). The legacy shared token remains valid and maps to
-- the OWNER role, so a dashboard upgrade never locks the owner out.
CREATE TABLE IF NOT EXISTS admin_users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE COLLATE NOCASE,
    password_hash TEXT NOT NULL,
    password_salt TEXT NOT NULL,
    iterations INTEGER NOT NULL,
    role TEXT NOT NULL CHECK(role IN ('OWNER','ADMINISTRATOR','MODERATOR','VIEWER')),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    last_login_at INTEGER
);

-- Server-wide dashboard preferences (timezone, maintenance mode, etc.).
-- player_uuid = '' is the global scope used by all_settings/globalSetting.
CREATE TABLE IF NOT EXISTS dashboard_settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

-- Console command audit for commands dispatched from the dashboard.
CREATE TABLE IF NOT EXISTS admin_console_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    actor_name TEXT NOT NULL,
    command TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

-- Scheduled announcements / tasks. Runs are best-effort while the server is up.
CREATE TABLE IF NOT EXISTS scheduled_tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL CHECK(kind IN ('ANNOUNCE','COMMAND')),
    payload TEXT NOT NULL,
    interval_minutes INTEGER NOT NULL,
    next_run_at INTEGER NOT NULL,
    last_run_at INTEGER,
    run_count INTEGER NOT NULL DEFAULT 0,
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL
);

-- One-time (non-recurring) announcements queued for the next tick.
CREATE TABLE IF NOT EXISTS announcements (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    message TEXT NOT NULL,
    actor_name TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    delivered_at INTEGER
);
