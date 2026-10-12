# FB-SMP 24/7 deployment

Run the server permanently on a Debian/Ubuntu VPS. This installs Paper,
Velocity, Geyser/Floodgate, ViaVersion/ViaBackwards, the playit.gg agent, the
admin panel ingress bridge and an external health watchdog as systemd services
that restart on crash and on reboot.

## One-shot provisioning (recommended)

On a fresh box, one command does everything - install prerequisites, clone the
repository to a stable path, run the installer, restore your existing world
from an encrypted bundle, arm Discord alerts, and verify:

```bash
sudo FBSMP_BUNDLE=/root/fbsmp-migrate.tar.gz.enc \
     FBSMP_BUNDLE_PASS_FILE=/root/migrate.pass \
     FBSMP_ALERT_WEBHOOK_FILE=/root/webhook.txt \
     bash deploy/vps-bootstrap.sh
```

Every input is optional. With no `FBSMP_BUNDLE` it installs a fresh server;
with no webhook it skips alerting. Re-running is safe: the world is only
replaced when a bundle is supplied.

The lower-level entrypoints are also available:

```bash
sudo bash deploy/provision-vps.sh   # clone/update + install only
```

Re-running it pulls the latest code and re-applies the install. The world and
secrets on the VPS are never overwritten. The GitHub auto-deploy workflow calls
this same script, so a push to `main` becomes a live update.

## Requirements

- A Debian 12+ / Ubuntu 22.04+ VPS (x86_64). 2 vCPU / 4 GB RAM / 20 GB disk minimum.
- Root access.
- The server must not be running anywhere else at the same time. Two copies
  sharing one world or one set of plugin data will corrupt it.

## Steps

1. Clone the repo on the VPS and build the plugin jars:

   ```bash
   git clone https://github.com/firebreadga359-bot/FB-SMP.net.git fb-smp
   cd fb-smp
   ```

   The installer builds the FB-SMP plugin jars automatically if they are
   missing, so no manual Gradle step is required.

2. Run the installer as root:

   ```bash
   sudo bash deploy/deploy.sh
   ```

   To reuse your existing playit.gg Bedrock address, pass the secret file:

   ```bash
   sudo PLAYIT_SECRET_FILE=/path/to/playit-account-secret bash deploy/deploy.sh
   ```

   Without a secret, the agent starts unclaimed; claim a tunnel afterwards with:

   ```bash
   sudo -u fbsmp /opt/fb-smp/playit/playit-cli --socket-path /tmp/p.sock setup
   ```

3. Check status:

   ```bash
   systemctl status fb-smp-paper fb-smp-velocity fb-smp-playit
   journalctl -u fb-smp-velocity -f
   ```

## Networking

- The server binds Velocity to `0.0.0.0:25565` (Java) and Geyser to UDP `19132`
  (Bedrock). Paper stays on `127.0.0.1:25566`, unreachable from outside.
- For internet play through playit.gg, **no inbound ports need to be open**.
- For direct-IP Java play, open inbound TCP 25565 in the VPS firewall.
- Bedrock through playit.gg uses the address shown in `journalctl -u fb-smp-playit`.

## Managing the services

```bash
sudo systemctl restart fb-smp-velocity   # after changing proxy config
sudo systemctl restart fb-smp-paper      # after changing backend config
sudo systemctl stop fb-smp-paper fb-smp-velocity fb-smp-playit
```

The installer also runs:

- `fb-smp-panel-bridge` — publishes the loopback admin panel (127.0.0.1:8765)
  on port 12000 so it is reachable without exposing Paper.
- `fb-smp-health` — checks the public Java and Bedrock addresses from a third
  party every 60s and restarts the playit unit if a tunnel is stuck (process up,
  unreachable). It also revives the panel bridge.

### Admin panel

- LAN/VPN: `http://<host-ip>:12000/`
- Public: put nginx/Caddy in front of `127.0.0.1:12000` with TLS.
- Or tunnel it over SSH: `ssh -L 12000:127.0.0.1:12000 user@host`, then open
  `http://127.0.0.1:12000/`.

Log in with the OWNER account; change the password after first login. The owner
token in `server/plugins/FBSMP/admin-token` is a break-glass OWNER credential —
rotate it once you are in.

Data lives in `/opt/fb-smp/server` (world, plugins, backups). Nightly backups
run at 04:30 via `fb-smp-backup.timer`, writing to `/opt/fb-smp/server/backups`.

## Moving an existing world and player data

To carry a live server over to the VPS without losing builds, balances or
Bedrock player identities, snapshot it and unpack the snapshot into
`/opt/fb-smp/server`.

On the **source** host (stop the server first - a running server makes the
world and SQLite database inconsistent):

```bash
# stop whatever is running the server, then:
sudo bash deploy/make-migration-bundle.sh /workspace/fb-smp/server /workspace/fbsmp-migrate.tar.gz
```

The bundle contains the world, `plugins/FBSMP` (economy DB, dashboard accounts,
shop config), the Floodgate `key.pem` (keeps Bedrock players linked), the
Velocity `forwarding.secret` and `server.properties`.

On the **VPS**, with the stack already installed and stopped:

```bash
sudo systemctl stop fb-smp-paper fb-smp-velocity fb-smp-playit \
                    fb-smp-panel-bridge fb-smp-health
sudo tar -xzf /root/fbsmp-migrate.tar.gz -C /opt/fb-smp/server
sudo chown -R fbsmp:fbsmp /opt/fb-smp/server
sudo systemctl start fb-smp-paper
sleep 25
sudo systemctl start fb-smp-velocity fb-smp-playit fb-smp-panel-bridge fb-smp-health
```

Then confirm the public addresses and, on the source host, **stop the old
server permanently** so two copies never share a world.

Reusing the source `playit.toml` (installed by `provision-vps.sh` from
`PLAYIT_SECRET_FILE`, or copied to `/opt/fb-smp/playit/playit.toml`) keeps the
same public Java and Bedrock addresses, so players do not need to change them.

## Upgrading a version

Edit the pinned versions/checksums at the top of `deploy/deploy.sh`, then re-run
it. Secrets are preserved; the world is never overwritten on re-run.

## Automatic deploys from GitHub

`.github/workflows/deploy-vps.yml` runs `provision-vps.sh` on the VPS after
every push to `main` (and on manual dispatch), so the repository is the single
source of truth and pushing is all it takes to update the live server.

Set these **repository secrets** (Settings -> Secrets and variables -> Actions):

| Secret | Required | Meaning |
|---|---|---|
| `VPS_HOST` | yes | Hostname or IP of the VPS |
| `VPS_USER` | yes | SSH user with passwordless sudo (or `root`) |
| `VPS_SSH_KEY` | yes | Private key whose public half is in that user's `~/.ssh/authorized_keys` |
| `VPS_PORT` | no | SSH port (default `22`) |
| `VPS_REPO_DIR` | no | Checkout path on the VPS (default `/opt/fb-smp-repo`) |

Prepare the VPS side once:

```bash
# on your workstation
ssh-keygen -t ed25519 -C fb-smp-deploy -f ~/.ssh/fbsmp_deploy -N ''
ssh-copy-id -i ~/.ssh/fbsmp_deploy.pub <VPS_USER>@<VPS_HOST>
# paste the private key (~/.ssh/fbsmp_deploy) into the VPS_SSH_KEY secret

# on the VPS: let the deploy user run the installer without a password
echo "<VPS_USER> ALL=(ALL) NOPASSWD:ALL" | sudo tee /etc/sudoers.d/fbsmp-deploy
sudo chmod 440 /etc/sudoers.d/fbsmp-deploy
```

Safety notes:

- The workflow fails fast with a clear message if the secrets are missing; it
  never touches a host it cannot authenticate to.
- The installer is idempotent. On re-run it preserves `/opt/fb-smp/server`
  (world, plugin data, secrets) and only refreshes code, jars and units.
- If you keep the live server in this sandbox as well, **stop one of them**.
  Two servers sharing a world will corrupt it.
