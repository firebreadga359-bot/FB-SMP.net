# FB-SMP Shop/Sell/Loan Upgrade — Phase A Report

Read-only inspection. Nothing was edited, stopped, or restarted.

## 1. Project path and how it was verified
- Active project: `/workspace/fb-smp` (persistent ext4 mount `/dev/nvme0n2`).
- The spec PDF landed in `/home/openhands/workspace/project` (a separate dir that
  holds only the PDF). `/workspace/project` is a different, unrelated checkout
  (inode 131073 vs 655361 for `/workspace/fb-smp`).
- Verified active by: it is the only checkout with the built plugin jar, the live
  `server/` directory, the SQLite DB and the migrations; it matches the git remote
  `firebreadga359-bot/FB-SMP.net`.
- NOTE: no live server is running right now. The container was restarted (uptime
  21 min at inspection time); Paper/Velocity/playit/tunnel are all stopped. No
  stop-gate is triggered (we must not start/stop anything).

## 2. Process tree and ports
- Only OpenHands infra is running (agent server :60000, VS Code server :60001).
- No Paper JVM, no Velocity JVM, no playit, no tunnel, no supervisor.
- Nothing listens on 25565/25566/19132.

## 3. Versions
| Component | Version |
|---|---|
| Minecraft / Paper | 26.1.2 (build 74), `paperVersion=26.1.2.build.74-stable` |
| Velocity | 4.2.0 |
| Geyser | 2.11.3 (build 1251) |
| Floodgate | 2.2.5 (build 141) |
| ViaVersion / ViaBackwards | 5.12.1 |
| Java (build) | 25 toolchain (`options.release=25`) |
| Build tool | Gradle (Kotlin DSL), wrapper present |
| Plugin | our own `net.firebreadmc.fbsmp` v1.0.0, `api-version: '26.1'` |

## 4. Active implementations for /shop, /sell, /loan
All three are **our own plugin** (not third-party), registered in
`fbsmp-paper/src/main/resources/plugin.yml` and dispatched in
`CommandRouter.onCommand`:
- `/shop` -> `CommandRouter.shop` + `menus/ShopMenu` (category GUI + quantity GUI);
  `/shop buy <item> [qty]` command path.
- `/sell` -> `CommandRouter.sell` -> `menus/SellMenu` (escrow GUI, V3 tables).
- `/loan` -> `CommandRouter.loan` (`/loan`, `/loan take <amt>`, `/loan repay <amt>`).
- Related: `/mort`, `/mortvu`, `/ah`, `/ausell`, `/gift`, `/orders`, `/bal`, `/pay`.
- Permissions today: only `fbsmp.admin`, `fbsmp.moderate`,
  `fbsmp.homes.unlimited`, `fbsmp.loan.limit.bypass`. **No `fbsmp.shop.*` /
  `fbsmp.sell.*` / `fbsmp.loan.use` nodes exist yet** (spec §11 wants them).
- Admin: `/fbsmp <version|backup|reload|debug|player|economy|warp|calendar>`.
  No shop/sell/loan admin commands exist yet.

## 5. Economy provider
- **No Vault, no external economy plugin.** Economy is internal:
  `players.balance_minor` (INTEGER, minor units) in SQLite.
- Currency: symbol `₣฿` (`Money.SYMBOL`); scale = 2 (`economy.yml decimal-places: 2`).
- Format: `Money.format(long minorUnits)` -> `₣฿1,234.56` (Locale.ROOT).
- Parsing: `Money.parseMinorUnits(String)` (strict, up to 2 dp, positive only).
- Type is **long minor units**, not double/BigDecimal. Spec's BigDecimal mandate
  maps to "use `long` minor units with exact integer math + `Math.*Exact`" — already
  the project's convention. Reuse it everywhere.

## 6. Calendar
- `FbCalendar` (`fbsmp-common`). 12 months, named; 11 regular months of 30 days +
  1 special month (`Yearsend`) of `special-month-days` (29).
- "Today" is computed from **real wall-clock time**:
  `elapsedDays = (System.currentTimeMillis() - calendarStartMillis) / (dayLengthMinutes*60_000)`,
  `dayLengthMinutes = 30`. Epoch stored in `calendar_state` table.
- Display format today: `"Year N, <MonthName> D"` — **not** the spec's `d.m.y`
  (the spec's `1.1.6` example does not match this project; project wins).
- No `DayChangeEvent`; a day boundary is purely time-derived, so a polling
  reconciler (spec §9.2 option 2) is the right mechanism.
- No year bounds beyond >= 1; year length is fixed (365 days).
- Note: `/time set` does NOT affect this calendar (it is real-time based).

## 7. Persistence
- SQLite via `org.xerial:sqlite-jdbc:3.50.3.0`, file
  `server/plugins/FBSMP/fbsmp.sqlite`. Flyway-style versioned migrations
  `db/migration/V1..V3__*.sql` tracked in `schema_migrations` (1,2,3 applied).
- Existing tables: players, economy_transactions, homes, warps, settings,
  ignored_players, auctions, loans, mortgages, community_orders,
  moderation_records, calendar_state, item_mailbox, property_ownership,
  sell_sessions, sell_session_items.
- Live data snapshot (read-only, via `mode=ro`, no copy of the WAL file):
  players=1, loans=1, economy_transactions=4, auctions=1, mortgages=0,
  community_orders=0, moderation_records=0.
- `loans` today: id, player_uuid, principal_minor, interest_minor,
  remaining_minor, due_at(real ms), status(ACTIVE|PAID|OVERDUE|DEFAULTED),
  created_at. No warning/overdue/timeout columns yet.
- All money is INTEGER minor units; all DB access serialized under one `lock`
  with explicit `setAutoCommit(false)` transactions. Idempotency is via
  UNIQUE keys on some tables (item_mailbox id, sell line ids).
- No `fbsmp_*` prefixed tables yet; new tables should follow the existing
  unprefixed style OR be prefixed — I will follow the existing style and add a
  `V4` migration (additive only).

## 8. GUI framework and Bedrock handling
- GUI framework: plain Bukkit inventories with custom `InventoryHolder`s
  (ShopMenu, SellMenu, WarpMenu, AuctionMenu, MortgageMenu, SettingsMenu). Reuse it.
- **No Floodgate API usage anywhere in the code** (grep found none). Bedrock
  players use chest GUIs through Geyser implicitly; there is no forms code.
  => Per spec §13, I must NOT add a Floodgate API dependency that isn't present;
  keep chest GUIs and ensure every action is plain-left-click reachable.
- SellMenu already tags staged items with a PDC key `fbsmp:sell_line_id` and has
  escrow/recovery — good precedent for the shop's `fbsmp:gui` marker.

## 9. Moderation / timeout options
- Internal `moderation_records` table + `ModerationListener`:
  - `AsyncPlayerPreLoginEvent` denies BAN (works for Java+Bedrock via proxy).
  - `AsyncPlayerChatEvent` blocks TIMEOUT (mute).
- No LiteBans/AdvancedBan/EssentialsX present.
- => Recommended mechanism for loan overdue timeouts: **Option B (internal)**,
  reusing `AsyncPlayerPreLoginEvent` to deny while `now < expires_at_ms`. This
  is exactly the existing pattern, restart-safe, real-time based. Default mode
  `PRELOGIN_DENY_AND_KICK`; a documented `RESTRICTED_MODE` alternative can be
  added later. Do not touch the existing BAN/TIMEOUT logic.

## 10. Files that will change / be created (planned)
Will change (minimal diff):
- `fbsmp-paper/src/main/resources/plugin.yml` — add permissions nodes only.
- `CommandRouter.java` — add subcommands under existing `/shop`, `/sell`, `/loan`;
  add `/shopadmin`, `/loanadmin` (or subcommands under `/fbsmp`).
- `FbsmpDatabase.java` — add loan v2 / shop / sell methods + new migration.
- New `db/migration/V4__shop_sell_loan_upgrade.sql` — additive tables/columns.
- `shop.yml`, `sell_prices.yml`, `loans.yml` — additive keys with defaults.
- `messages.yml` — additive message keys.
Will create (new classes, existing package):
- `shop/` catalog+GUI+pricing, `sell/` policy+GUI, `loan/` calendar adapter,
  interest, scheduler, timeout, repository; `pricing/PriceRegistry`,
  `PriceValidator`; `audit/AuditLog`; `economy/` is already internal (wrap only).
Offline tooling: a `PriceValidator` runnable as a JUnit test and/or a Python
script reading the exported item registry.

## 11. Risks, stop-gates, open questions
- STOP GATES: none triggered. Active implementation identified; economy is
  internal (abstractable); calendar is real-time (reconciler works); no live
  restart required for the staged work; resources are healthy
  (15.9 GB RAM, 24 GB free disk, load 0.14).
- RISK (spec §18.2 #4, §8.6): the calendar is **real-time based**, so a loan
  "due date" computed from calendar days is equivalent to a real-time deadline.
  The spec insists calendar vs real time be separated for the timeout (uses
  real seconds) — that is satisfiable; the due-date itself is calendar-based.
- RISK: spec's `d.m.y` date format vs project's `Year N, Month D`. Project wins;
  I will implement a strict `d.m.y` parser/serializer **in addition**, storing
  both a day index and a display string, and show the project's existing format
  in `/loan` output to stay consistent. Flag for owner confirmation.
- OPEN QUESTION: spec wants `d.m.y`; project shows `Year N, Month D`. Confirm
  which the owner wants as the canonical loan display. Default: match project.
- OPEN QUESTION: should the shop catalog be the full registry (thousands of
  items) with generated prices, or stay curated? Spec says full registry. I will
  generate the full registry catalog with a `needs_review` list, fail-closed
  (unpriced = disabled), calibrated to the existing shop.yml anchors.
- Version note: `plugin.yml api-version: '26.1'`; `Registry.MATERIAL`/`Material`
  availability to be confirmed at compile time (Java 25 / Paper 26.1.2).

## Phase B readiness
Phase B (implementation) will be done in `_staging/fb-smp-upgrade/<ts>/` and in
new source files; **nothing will be written into the live `server/plugins/`
while anything is running, and no live data or DB will be modified.**
