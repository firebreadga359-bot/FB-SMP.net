# FB-SMP repository notes

Minecraft Java + Bedrock survival server. Paper backend behind a Velocity proxy,
Geyser + Floodgate for Bedrock. All custom gameplay (economy, shop, sell, loan,
auction house, mortgages, orders, homes, warps, moderation) lives in the
`fbsmp-paper` plugin module, backed by a single SQLite database.

## Build and test

- Gradle (Kotlin DSL), multi-module: `fbsmp-common`, `fbsmp-paper`, `fbsmp-velocity`.
- Build: `./gradlew :fbsmp-paper:build` (produces `fbsmp-paper-<version>.jar`).
- Tests: `./gradlew test` (JUnit 5; unit tests in `fbsmp-common`, DB tests in `fbsmp-paper`).
- Java 25 toolchain. The live server runs from `server/` with `paper.jar` + `velocity.jar`.

## Data layer

- Single SQLite DB at `server/plugins/FBSMP/fbsmp.sqlite`, opened by
  `FbsmpDatabase`. Migrations are versioned in `schema_migrations` (currently V4).
- Money is stored as **minor units** (long cents). Format with
  `Money.format(minor)` -> `₣฿1,234.56`. Parse with `Money.parseMinorUnits`.
- Balance changes must go through `credit(...)` / `debit(...)` /
  `transfer(...)` / `debitAndMail(...)` so the `economy_transactions` ledger stays
  consistent. Never write `players.balance_minor` directly.
- `writeAudit(actorUuid, actorName, action, target, beforeJson, afterJson, reason, now)`
  records administrative actions in `fbsmp_audit_log`. Every sensitive admin
  action must write an audit row.
- Economy provider is **custom (SQLite)**, not Vault. There is no Vault dependency.

## Admin panel

- `net.firebreadmc.fbsmp.paper.admin.PrivateAdminPanel` is a loopback-only
  `com.sun.net.httpserver.HttpServer` (default `127.0.0.1:8765`, token from
  `plugins/FBSMP/admin-token`). It is safe by default but only reachable from
  inside the container.
- Config: `admin-panel.enabled`, `admin-panel.bind-address`, `admin-panel.port`.
- Auth model: single shared owner token or a per-user account
  (`admin_users` table) with role-scoped permissions, HttpOnly session cookie,
  CSRF header (`X-CSRF-Token`), 30-minute session lifetime, login rate limiting.
- The session cookie gets `Secure` automatically when `X-Forwarded-Proto: https`
  is present, so a TLS-terminating proxy produces a fully hardened cookie.
- `handle()` must close the exchange in a `finally`, never in a
  `try (exchange)` block: `HttpExchange.close()` also closes the response body,
  so closing before the `catch` blocks run makes every 401/403/404/500 response
  an empty reply. Error paths also `drain(exchange)` (consume the request body
  once, cached in a `ThreadLocal<byte[]>`) so the JDK server does not reset the
  connection before delivering the error.
- Public reachability: the platform publishes container port **12000** at
  `https://work-1-iauqtpbjyihltdtb.prod-runtime.all-hands.dev`. `tools/panel_bridge.py`
  is a loopback-only reverse proxy that listens on `0.0.0.0:12000`, forwards
  `/` and `/api/*` to `127.0.0.1:8765`, rewrites `Host` to a loopback value,
  adds `X-Forwarded-Proto: https`, and rejects every other path. Keep the panel
  itself bound to loopback; the bridge (not the panel) is the public surface.
  `/workspace/panel-bridge-watchdog.sh` restarts the bridge if it dies. It runs
  separately from `supervise.sh` because `supervise.sh`'s `flock` is inherited by
  the long-lived Paper/Velocity processes, so that script cannot be restarted
  without a full Minecraft restart (it exits with "another supervisor already
  holds /workspace/supervise.lock"). `supervise.sh` also has an
  `ensure_panel_bridge()` hook for the next cold start.

## Running the stack

- The container has no init system; nothing starts at boot. `~/.bashrc` runs
  `/workspace/start-fbsmp.sh` so the stack recovers when a shell starts after a
  restart. `start-fbsmp.sh` clears stale `supervise.lock`/`playit.sock`, then
  launches `supervise.sh` (Paper + Velocity + Geyser + playit).
- Public addresses: Java `cairo-paste.tun.ply.gg:25565`, Bedrock
  `fried-toilette.tun.ply.gg:37298`, admin panel
  `https://work-1-iauqtpbjyihltdtb.prod-runtime.all-hands.dev/`.
- Verify from outside the container: the container's egress allowlist does not
  include playit's tunnel edges, so a local ping of `cairo-paste.tun.ply.gg`
  resets even when the tunnel is healthy. Use a third-party checker instead,
  e.g. `curl -s https://api.mcstatus.io/v2/status/java/cairo-paste.tun.ply.gg:25565`.
- playit's API may assign a tunnel edge whose IP is not on the container's
  allowlist; the agent then loops on `control session expired`. This is benign
  while an edge the agent can reach still forwards traffic.

## Uptime

- This workspace is **not** a 24/7 host. `OH_RUNTIME_IDLE_TIMEOUT_SECONDS=1200`
  stops the runtime after ~20 min idle, and there is no init system. Data on
  `/workspace` (ext4, a separate disk) survives; the runtime does not.
- Two watchdogs run while the runtime is alive:
  - `supervise.sh` — restarts Paper/Velocity/playit when a *process* dies.
  - `fbsmp-health.sh` — checks the public addresses from outside and restarts
    the playit agent when a tunnel is *stuck* (process up, unreachable). It also
    restarts the panel bridge. Log: `/workspace/health.log`.
- OpenHands scheduled automations run in a **separate sandbox**, so they cannot
  revive this server. They can only alert (e.g. post to Slack) when it is down.
- `bash /workspace/make-deploy-bundle.sh` builds `/workspace/fbsmp-deploy.tar.gz`
  — a complete, portable deploy (jars, world, FBSMP data, panel bridge, scripts,
  systemd units) for a real always-on host.
- `deploy/deploy.sh` (the VPS installer) also ships `fbsmp-health.sh` and
  `tools/panel_bridge.py` and installs `fb-smp-panel-bridge` + `fb-smp-health`
  systemd units. On a VPS set `FBSMP_MANAGE_PLAYIT=0` so the watchdog restarts
  the playit *unit* instead of starting a duplicate agent.
- `deploy/provision-vps.sh` is the one-shot entrypoint: clone/update the repo to
  `/opt/fb-smp-repo`, then run `deploy/deploy.sh`. It is idempotent and preserves
  `/opt/fb-smp/server`. `.github/workflows/deploy-vps.yml` calls it over SSH on
  every push to `main` (needs `VPS_HOST`/`VPS_USER`/`VPS_SSH_KEY` repo secrets).
- The workflow only runs from the default branch, so it activates once the
  deploy work is on `main` (i.e. after this PR merges).
- `fbsmp-health.sh` is portable: it reads all paths from `FBSMP_*` env vars
  (container defaults), checks Java/Bedrock through `api.mcstatus.io`, and can
  post de-duplicated down/up alerts to a webhook via `FBSMP_ALERT_WEBHOOK` or
  a `.fbsmp-alert-webhook` file. `api.mcstatus.io` rejects the default Python
  user-agent with 403 — always send a `User-Agent` header.
- Discord webhook alerts: the URL lives only in `.fbsmp-alert-webhook` (gitignored)
  and the `DISCORD_WEBHOOK_URL` agent-server secret — never in the repo. Payloads
  include `allowed_mentions: {"parse": []}`. Discord's edge (Cloudflare) returns
  **403 code 1010** for POSTs with no `User-Agent`; curl passes by default but
  Python `urllib` must send one.

## Shop and sell

- Prices live in `plugins/FBSMP/shop.yml` (buy) and `sell_prices.yml` (sell),
  loaded by `ShopPricing` into a validated `PriceRegistry` + `ShopCatalog`.
- Sell prices must stay strictly below the matching buy price.
- Reload after edits with `/shopadmin reload` (or restart). `/shop`, `/sell`,
  `/ah`/`aushop`, `/ausell`, `/loan` are all handled by `CommandRouter`.

## Conventions

- Keep public API and identifiers stable; add features additively.
- Avoid `set -e` in shell scripts run by the agent runtime.
- Secrets must never be committed: `admin-token`, `forwarding.secret`,
  `playit.toml`, `*.sqlite` are runtime files.
