# Make FB-SMP always on (24/7)

FB-SMP must run on a host that stays powered and online. The OpenHands agent
sandbox is **not** such a host: it stops after about 20 minutes idle
(`OH_RUNTIME_IDLE_TIMEOUT_SECONDS=1200`) and has no init system, so nothing
restarts on its own. Pick a real host below.

## Choose a host

| Option | Cost | Best for | Notes |
|---|---|---|---|
| VPS (Debian/Ubuntu) | paid | a real public server | Recommended. One command installs everything as systemd services that restart on crash and reboot. |
| GitHub Codespace + keep-alive | free tier | a hobby server you can babysit | Codespaces sleep after ~30 min; `.github/workflows/codespace-keepalive.yml` wakes it every 15 min. Not a guarantee. |
| Home machine / Raspberry Pi | free | always-on LAN box | Forward ports or use the playit.gg tunnel. |

The VPS path is the only one that survives a reboot with no outside help.

## Install on a VPS (one command)

On a fresh Debian 12+ / Ubuntu 22.04+ box with root:

```bash
git clone https://github.com/firebreadga359-bot/FB-SMP.net.git fb-smp
cd fb-smp
sudo bash deploy/vps-bootstrap.sh
```

This installs the JDK, Paper, Velocity, Geyser/Floodgate, ViaVersion, the
playit.gg tunnel agent, the admin-panel bridge, and an external health watchdog
as systemd services. It builds the plugins, restores your world if you pass
`FBSMP_BUNDLE=`, and can arm Discord alerts with `FBSMP_ALERT_WEBHOOK_FILE=`.

The installer is idempotent. Re-running it refreshes code and jars and never
overwrites `/opt/fb-smp/server` (world, economy database, secrets).

Full details and the move-your-world steps are in [`deploy/README.md`](README.md).

## Automatic deploys

`.github/workflows/deploy-vps.yml` re-runs the installer over SSH on every push
to `main`. Add these repository secrets to enable it:

- `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY` (required)
- `VPS_PORT`, `VPS_REPO_DIR` (optional)

Pushing to the repository then updates the live server. See
`deploy/README.md` ("Automatic deploys from GitHub").

## Keep it online

- systemd restarts each service on crash (`Restart=always`).
- `fb-smp-health` checks the public Java and Bedrock addresses from a third
  party every 60 seconds and restarts a stuck playit tunnel. It also revives the
  panel bridge.
- `fb-smp-backup.timer` takes a consistent SQLite snapshot nightly at 04:30.
- Optional: post outage and recovery alerts to a Discord webhook by writing the
  URL to `/opt/fb-smp/.fbsmp-alert-webhook` and restarting `fb-smp-health`.

Check status any time:

```bash
systemctl status fb-smp-paper fb-smp-velocity fb-smp-playit \
                 fb-smp-panel-bridge fb-smp-health
journalctl -u fb-smp-velocity -f
```

## Verify from outside

The container or host cannot always reach the playit tunnel edges, so test from
a third party. This is the same check the public site uses:

```bash
curl -s -A "fb-smp/1.0" \
  "https://api.mcstatus.io/v2/status/java/cairo-paste.tun.ply.gg:25565"
curl -s -A "fb-smp/1.0" \
  "https://api.mcstatus.io/v2/status/bedrock/fried-toilette.tun.ply.gg:37298"
```

`"online":true` means players can connect. The public page at
<https://fb-smp.net> shows this live for visitors.

## Public addresses

- Java: `cairo-paste.tun.ply.gg:25565`
- Bedrock: `fried-toilette.tun.ply.gg:37298`
- Admin panel: loopback `127.0.0.1:8765`, published on port 12000 by the bridge.
  Put nginx/Caddy in front of it with TLS, or reach it over an SSH tunnel.

Reuse the playit secret when you move hosts so the public addresses stay the
same. See `deploy/README.md` ("Moving an existing world and player data").

## Safety

Never run two copies of the server against one world or one economy database.
Two copies will corrupt it. Stop the old copy before you start the new one.
