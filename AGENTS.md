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
- Item transfers that remove a stack from a player inventory (`/gift <player> item`,
  `/ausell`) must first record it with `createItemEscrow(...)` (table
  `item_escrow`, V6). The row is cleared only when the destination is durable:
  `deliverEscrowTo(...)` (gift -> recipient mail) or `createAuction(..., escrowId)`
  (listing) clear it in the same transaction as the destination write.
  `recoverItemEscrow(now)` runs once at startup and returns any surviving row to
  its owner's mailbox, so a crash can never both keep the escrow and lose the
  item. Never delete the inventory stack before the escrow row is committed.
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
- `deploy/vps-bootstrap.sh` is the full one-command bootstrap (prereqs, clone,
  install, optional encrypted world restore, optional Discord webhook, verify).
- The installer is dry-run tested in a privileged systemd container
  (`jrei/systemd-debian:12`) before trusting it on a real box. `deploy.sh` must
  install `python3`, `export` every `fb-smp.env` var (run wrappers `exec`, so
  unexported vars are lost), quote values containing spaces, give the playit unit
  a `RuntimeDirectory=playit` (its IPC socket is `/run/playit/playitd.sock`), and
  copy only the main plugin jars (never `-plain`/`-sources`, which have no
  `plugin.yml` and make Paper fail to load).
- `deploy/make-migration-bundle.sh` snapshots a stopped server's world,
  `plugins/FBSMP`, Floodgate `key.pem`, `forwarding.secret`, `server.properties`
  and the playit secret (`/workspace/playit-agent/playit.toml`) into
  `/workspace/fbsmp-migrate.tar.gz` for moving to a VPS. Unpack into
  `/opt/fb-smp/server`; reuse the playit secret to keep the same public
  addresses. It refuses to run while `paper.jar` is alive.
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

## Website (`site/`)

- The site is **multi-page and static** (no build step), sharing
  `site/assets/site.css` (design system) and `site/assets/site.js` (behaviour).
  Pages: `index.html` (home), `features.html`, `join.html`, `stats.html`,
  `rules.html`, `faq.html`, `changelog.html`, `signup.html`, `404.html`.
  Every page repeats the same `<header class="nav">` and `<footer>`; when adding
  a page, copy those and update all nav lists together.
- `site.js` is the single source of behaviour: live server status, the
  control-room address, GitHub commits (`#commit-log`), open issues
  (`[data-issues]`), uptime issues (`#alert-log`), copy buttons (`[data-copy]`),
  scroll reveals (`.reveal`), counters (`[data-count]`), the mobile nav, and the
  invite form. It is keyed off element ids/`data-*` attributes, so a page only
  opts into a feature by including the matching markup.
- Live status fetches `https://api.mcstatus.io/v2/status/{java,bedrock}/<host>:<port>`
  in the browser (the API sends `access-control-allow-origin: *`, so no proxy is
  needed). It refreshes every 60s and on a button. Keep the addresses in the
  `JAVA`/`BEDROCK` JS objects in sync with the playit tunnels.
- The invite form posts JSON to `/_forms/join` and only works on the form hosts
  listed in `FORM_HOSTS` (`fb-smp.birta.link`, `fb-smp.net`, `www.fb-smp.net`);
  off-host it hides the form card and reveals `#signup-fallback`, which points at
  a GitHub issue instead. Without this, submitting off-host would just reload the
  page — the handler must stay.
- Published to GitHub Pages by `.github/workflows/pages.yml` on pushes to `main`
  under `site/`. The repo root is a Minecraft
  server, so only `site/` is uploaded.
- **Custom domain is set up but not live yet.** `fb-smp.net` is added as a
  Cloudflare zone (`d1b9402fa02ced63a5189317b274fd7f`), which is still
  **pending**: the registrar still serves GoDaddy nameservers
  (`ns75/ns76.domaincontrol.com`) instead of the assigned
  `nicole.ns.cloudflare.com` / `todd.ns.cloudflare.com`. Until those are
  changed, the public apex keeps answering from GoDaddy's website builder and
  the custom domain serves the old host.
- The Cloudflare zone already holds the correct records for after the cutover:
  apex `A` to the four GitHub Pages IPs (`185.199.108-111.153`, **DNS-only** so
  GitHub's own TLS is used — proxying without Full SSL would loop), `www` CNAME
  to `firebreadga359-bot.github.io` (DNS-only), and `panel.fb-smp.net` CNAME to
  the named tunnel (proxied). The old GoDaddy-builder apex records were deleted.
  Rebuild with the Cloudflare API if they drift.
- GitHub Pages is configured with `cname: fb-smp.net` (via
  `PUT /repos/{owner}/{repo}/pages`), and `https_enforced` is off until the
  certificate is issued. Pages serves the site today at
  `https://firebreadga359-bot.github.io/FB-SMP.net/`.
- To finish: change the registrar's nameservers to the two Cloudflare ones. The
  zone then activates, the records above go live, and GitHub issues the
  certificate.

## 24/7 host (GitHub Codespace)

- The live host is the codespace `fb-smp-24h-4qv59g697vjwcgjx` (owner
  `firebreadga359-bot`). The server runs from `/workspaces/fb-smp/server`;
  data (world, `plugins/FBSMP/fbsmp.sqlite`) lives there and survives restarts.
- Processes are managed by `/workspaces/fbsmp-keeper.sh` (detached) -> it starts
  and revives `/workspaces/supervise.sh`, `panel_bridge.py`, `fbsmp-health.sh`,
  and playit. `.bashrc` relaunches the keeper on an interactive shell.
- `.github/workflows/codespace-keepalive.yml` (needs
  `CODESPACE_KEEPALIVE_TOKEN`) starts the codespace, SSHes in, starts the keeper
  if missing, ensures the public panel tunnel, and restarts playit when a tunnel
  is down. It runs every 15 min.
- **The `...-12000.app.github.dev` panel URL does not work.** Codespaces
  publishes forwarded ports through the `codespaces vmagent` systemd service,
  and a codespace is a container with no systemd, so the agent crashes on start
  (`Unable to resolve service for type ...ICurrentMetrics`) and GitHub's tunnel
  service never learns about port 12000 (`gh codespace ports` returns 404).
  Only connecting the VS Code web UI starts it. Instead the loopback panel is
  published by `deploy/fbsmp-panel-tunnel.sh`, a Cloudflare quick tunnel
  (`cloudflared` in `/workspaces`) that needs no init system. The keeper and the
  keep-alive workflow both ensure it. The current public URL is written to
  `/workspaces/panel-url.txt` (a quick-tunnel hostname, so it changes whenever
  the tunnel restarts; use a named tunnel on a real domain for a stable address).
  The panel keeps its own login, per-session CSRF, and rate limiting.
- `.github/workflows/uptime-alert.yml` checks the public Java, Bedrock, and panel
  endpoints every 5 min and opens an issue labelled `uptime` when one is
  unreachable, closing it on recovery (`deploy/uptime-alert/check.sh`). It needs
  no extra secret: the workflow token and `gh`. There is also a Discord/Slack
  webhook option in `deploy/uptime-alert/` and `fbsmp-health.sh`
  (`FBSMP_ALERT_WEBHOOK`).
- The keep-alive run writes the current tunnel URL to `panel-url.txt` on `main`;
  `site/index.html` reads that file so the landing page always links to the live
  control room. A quick-tunnel hostname changes on every restart, so the URL is
  not fixed; a stable name needs a Cloudflare named tunnel or a serveo key.
- There is **no init/systemd** in a codespace, so after the host is stopped for
  inactivity and resumed, everything is gone until either the next keep-alive
  run or an interactive shell starts the keeper. A non-interactive `gh codespace
  ssh` does **not** run `.bashrc`, so it will not revive the keeper by itself.
- The panel also has a **named tunnel** for a stable address,
  `https://panel.fb-smp.net`. `deploy/cf-named-tunnel-setup.sh` rebuilds
  `cf-credentials.json` from the Cloudflare API (idempotent), and
  `deploy/fbsmp-named-tunnel.sh` runs it. **It only resolves once the zone is
  active**: `fb-smp.net` is added in Cloudflare as `nicole.ns.cloudflare.com` /
  `todd.ns.cloudflare.com`, but the registrar still serves GoDaddy nameservers,
  so the hostname is NXDOMAIN until those are changed. Until then the quick
  tunnel stays the live link and the keep-alive run switches `panel-url.txt` to
  `panel.fb-smp.net` automatically once it serves.
- To revive by hand: clear stale `keeper.lock`/`supervise.lock`, then
  `setsid nohup /workspaces/fbsmp-keeper.sh >>/workspaces/keeper.log 2>&1 </dev/null &`.
  The keeper rebuilds `logs/` and restarts the stack. Detached processes survive
  an SSH disconnect. Never `pkill -f paper.jar` from a command line that itself
  contains that string, or you kill your own SSH session.
- For a real always-on host, use the VPS path in `deploy/ALWAYS-ON.md`.

## Running it 24/7

- `deploy/ALWAYS-ON.md` is the short "go always-on" runbook. The sandbox cannot
  be a 24/7 host (`OH_RUNTIME_IDLE_TIMEOUT_SECONDS=1200`, no init). The VPS path
  (`sudo bash deploy/vps-bootstrap.sh`) is the only one that survives a reboot
  with no outside help.

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
