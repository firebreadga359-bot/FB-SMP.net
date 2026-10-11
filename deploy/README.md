# FB-SMP 24/7 deployment

Run the server permanently on a Debian/Ubuntu VPS. This installs Paper,
Velocity, Geyser/Floodgate, ViaVersion/ViaBackwards and the playit.gg agent as
systemd services that restart on crash and on reboot.

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

Data lives in `/opt/fb-smp/server` (world, plugins, backups). Nightly backups
run at 04:30 via `fb-smp-backup.timer`, writing to `/opt/fb-smp/server/backups`.

## Upgrading a version

Edit the pinned versions/checksums at the top of `deploy/deploy.sh`, then re-run
it. Secrets are preserved; the world is never overwritten on re-run.
