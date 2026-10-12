#!/usr/bin/env bash
#
# FB-SMP 24/7 deployment installer
# --------------------------------
# Installs the FB-SMP Paper + Velocity + Geyser/Floodgate stack as systemd
# services on a fresh Debian/Ubuntu VPS, so it runs permanently and restarts
# automatically after crashes or reboots.
#
# Run from inside a clone of the FB-SMP repository:
#     sudo bash deploy/deploy.sh
#
# Optional environment variables:
#     PLAYIT_SECRET_FILE=/path/to/secret   reuse an existing playit.gg secret
#     PAPER_XMS=2G PAPER_XMX=4G            backend heap (raise on bigger boxes)
#     VELOCITY_XMS=512M VELOCITY_XMX=1G    proxy heap
#     INSTALL_DIR=/opt/fb-smp              install location
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Pinned artifact versions (must agree with gradle.properties / README)
# ---------------------------------------------------------------------------
PAPER_VER=26.1.2; PAPER_BUILD=74
PAPER_SHA=1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7
VELOCITY_VER=4.2.0; VELOCITY_BUILD=30
VELOCITY_SHA=35a5596a5468a035d8a32c8de5ebb0dc6b8d8f0cc3ff5169d514aca762af8aa8
GEYSER_VER=2.11.3; GEYSER_BUILD=1251
GEYSER_SHA=dd963f2123704b89c47d3775b908b189b8174b1650008c51d37b1026d6dd928e
FLOODGATE_VER=2.2.5; FLOODGATE_BUILD=141
FLOODGATE_SHA=649835022de37ff09255fc626d4bfe7bc2a3b926d07b0dfaf1f51e12bf37001d
VIA_VER=5.12.1
VIA_SHA=b8033807e7182c35003a45962352dd642c1e91880ca281da3a43cb6f481a95a2
VIA_BW_SHA=29d94d3a26c85c8a327574b2322db4078992ae457f0173325431a12aac384911
PLAYIT_VER=v1.0.10
JDK_MAJOR=25

INSTALL_DIR="${INSTALL_DIR:-/opt/fb-smp}"
PAPER_XMS="${PAPER_XMS:-2G}"; PAPER_XMX="${PAPER_XMX:-4G}"
VELOCITY_XMS="${VELOCITY_XMS:-512M}"; VELOCITY_XMX="${VELOCITY_XMX:-1G}"
SERVICE_USER="${SERVICE_USER:-fbsmp}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
STAGE="$INSTALL_DIR/.stage"
SERVER="$INSTALL_DIR/server"

say() { printf '\n==> %s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

fetch() { # fetch <url> <dest> <sha256>
  local url="$1" dest="$2" sha="$3"
  curl -fsSL --retry 3 --retry-delay 2 -o "$dest" "$url"
  echo "${sha}  ${dest}" | sha256sum -c - >/dev/null \
    || die "checksum mismatch for $dest"
}

# ---------------------------------------------------------------------------
# 0. Preflight
# ---------------------------------------------------------------------------
[[ "$(id -u)" -eq 0 ]] || die "run as root (sudo bash deploy/deploy.sh)"
[[ -f "$REPO_ROOT/start.sh" ]] || die "run this from inside the FB-SMP repo (missing $REPO_ROOT/start.sh)"
command -v systemctl >/dev/null || die "systemd is required (this script targets Debian/Ubuntu VPS)"

say "FB-SMP installer: repo=$REPO_ROOT install=$INSTALL_DIR"
command -v curl >/dev/null || { apt-get update -y && apt-get install -y curl ca-certificates; }

# Build the FB-SMP plugin jars if they are not already present (they are build
# artifacts and are not committed). Uses the repo's Gradle wrapper + Java 25.
build_plugins() {
  command -v java >/dev/null 2>&1 || return 1
  [[ -x "$REPO_ROOT/gradlew" ]] || return 1
  # Gradle needs to run at least on Java 25 for this project.
  if ! java -version 2>&1 | grep -q '"25'; then
    say "Default java is not 25; cannot auto-build plugins (build them manually and re-run)"
    return 1
  fi
  say "Building FB-SMP plugins with ./gradlew build"
  ( cd "$REPO_ROOT" && ./gradlew --no-daemon build -x test ) || return 1
  return 0
}


# ---------------------------------------------------------------------------
# 1. Base packages + Java 25 runtime
# ---------------------------------------------------------------------------
say "Installing base packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y curl ca-certificates tar sqlite3 coreutils

if [[ -x "$INSTALL_DIR/jdk25/bin/java" ]] && "$INSTALL_DIR/jdk25/bin/java" -version 2>&1 | grep -q "\"$JDK_MAJOR"; then
  say "Java $JDK_MAJOR already installed at $INSTALL_DIR/jdk25"
else
  say "Installing Temurin JDK $JDK_MAJOR"
  mkdir -p "$INSTALL_DIR/jdk25"
  curl -fsSL --retry 3 -o "$STAGE.tmp-jdk.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/${JDK_MAJOR}/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar -xzf "$STAGE.tmp-jdk.tar.gz" -C "$INSTALL_DIR/jdk25" --strip-components=1
  rm -f "$STAGE.tmp-jdk.tar.gz"
fi
JAVA_BIN="$INSTALL_DIR/jdk25/bin/java"
"$JAVA_BIN" -version

# Make Gradle use this JDK, then build the FB-SMP plugin jars if absent.
export JAVA_HOME="$INSTALL_DIR/jdk25"
export PATH="$JAVA_HOME/bin:$PATH"
if ! compgen -G "$REPO_ROOT/fbsmp-paper/build/libs/fbsmp-paper-*.jar" >/dev/null \
   || ! compgen -G "$REPO_ROOT/fbsmp-velocity/build/libs/fbsmp-velocity-*.jar" >/dev/null; then
  build_plugins || say "WARNING: FB-SMP plugin jars were not built. Copy them into $SERVER/plugins/ manually."
fi

# ---------------------------------------------------------------------------
# 2. Service user + directories
# ---------------------------------------------------------------------------
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  say "Creating service user $SERVICE_USER"
  useradd --system --home "$INSTALL_DIR" --shell /usr/sbin/nologin "$SERVICE_USER"
fi
mkdir -p "$STAGE" "$SERVER/logs" "$SERVER/backups" "$SERVER/config" \
         "$SERVER/plugins" "$INSTALL_DIR/playit"

# ---------------------------------------------------------------------------
# 3. Download server jars + plugins (verified)
# ---------------------------------------------------------------------------
say "Downloading Paper $PAPER_VER-$PAPER_BUILD"
fetch "https://fill-data.papermc.io/v1/objects/${PAPER_SHA}/paper-${PAPER_VER}-${PAPER_BUILD}.jar" \
      "$STAGE/paper.jar" "$PAPER_SHA"

say "Downloading Velocity $VELOCITY_VER-$VELOCITY_BUILD"
fetch "https://fill-data.papermc.io/v1/objects/${VELOCITY_SHA}/velocity-${VELOCITY_VER}-${VELOCITY_BUILD}.jar" \
      "$STAGE/velocity.jar" "$VELOCITY_SHA"

say "Downloading Geyser-Velocity $GEYSER_VER (build $GEYSER_BUILD)"
fetch "https://download.geysermc.org/v2/projects/geyser/versions/${GEYSER_VER}/builds/${GEYSER_BUILD}/downloads/velocity" \
      "$STAGE/Geyser-Velocity.jar" "$GEYSER_SHA"

say "Downloading Floodgate-Velocity $FLOODGATE_VER (build $FLOODGATE_BUILD)"
fetch "https://download.geysermc.org/v2/projects/floodgate/versions/${FLOODGATE_VER}/builds/${FLOODGATE_BUILD}/downloads/velocity" \
      "$STAGE/floodgate-velocity.jar" "$FLOODGATE_SHA"

say "Downloading ViaVersion / ViaBackwards $VIA_VER"
fetch "https://github.com/ViaVersion/ViaVersion/releases/download/${VIA_VER}/ViaVersion-${VIA_VER}.jar" \
      "$STAGE/ViaVersion-${VIA_VER}.jar" "$VIA_SHA"
fetch "https://github.com/ViaVersion/ViaBackwards/releases/download/${VIA_VER}/ViaBackwards-${VIA_VER}.jar" \
      "$STAGE/ViaBackwards-${VIA_VER}.jar" "$VIA_BW_SHA"

say "Downloading playit agent $PLAYIT_VER"
curl -fsSL --retry 3 -o "$STAGE/playit" \
  "https://github.com/playit-cloud/playit-agent/releases/download/${PLAYIT_VER}/playit-linux-amd64"

# ---------------------------------------------------------------------------
# 4. Lay out the server directory from the repo
# ---------------------------------------------------------------------------
say "Copying repository configuration"
cp "$STAGE/paper.jar"       "$SERVER/paper.jar"
cp "$STAGE/velocity.jar"    "$SERVER/velocity.jar"
cp "$STAGE/Geyser-Velocity.jar"      "$SERVER/plugins/Geyser-Velocity.jar"
cp "$STAGE/floodgate-velocity.jar"   "$SERVER/plugins/floodgate-velocity.jar"
cp "$STAGE/ViaVersion-${VIA_VER}.jar"   "$SERVER/plugins/ViaVersion-${VIA_VER}.jar"
cp "$STAGE/ViaBackwards-${VIA_VER}.jar" "$SERVER/plugins/ViaBackwards-${VIA_VER}.jar"

# Proxy + backend config templates from the repo
[[ -f "$REPO_ROOT/velocity.toml" ]] && cp "$REPO_ROOT/velocity.toml" "$SERVER/velocity.toml"
[[ -f "$REPO_ROOT/server.properties" ]] && cp "$REPO_ROOT/server.properties" "$SERVER/server.properties"
for f in bukkit.yml spigot.yml commands.yml help.yml permissions.yml ops.json \
         banned-ips.json banned-players.json whitelist.json usercache.json; do
  [[ -f "$REPO_ROOT/$f" ]] && cp "$REPO_ROOT/$f" "$SERVER/$f"
done
if [[ -f "$REPO_ROOT/config/paper-global.yml" ]]; then
  cp "$REPO_ROOT/config/paper-global.yml" "$SERVER/config/paper-global.yml"
elif [[ -f "$REPO_ROOT/paper-global.yml" ]]; then
  cp "$REPO_ROOT/paper-global.yml" "$SERVER/config/paper-global.yml"
fi

# FB-SMP plugin jars (build them with ./gradlew build if missing)
if compgen -G "$REPO_ROOT/fbsmp-paper/build/libs/fbsmp-paper-*.jar" >/dev/null; then
  cp "$REPO_ROOT/fbsmp-paper/build/libs/fbsmp-paper-"*.jar "$SERVER/plugins/"
fi
if compgen -G "$REPO_ROOT/fbsmp-velocity/build/libs/fbsmp-velocity-*.jar" >/dev/null; then
  cp "$REPO_ROOT/fbsmp-velocity/build/libs/fbsmp-velocity-"*.jar "$SERVER/plugins/"
fi
[[ -f "$REPO_ROOT/plugins/fbsmp-paper-1.0.0.jar" ]] && cp "$REPO_ROOT/plugins/fbsmp-paper-1.0.0.jar" "$SERVER/plugins/"
[[ -f "$REPO_ROOT/plugins/fbsmp-velocity-1.0.0.jar" ]] && cp "$REPO_ROOT/plugins/fbsmp-velocity-1.0.0.jar" "$SERVER/plugins/"

# Geyser config: Bedrock listener + Floodgate auth
mkdir -p "$SERVER/plugins/Geyser-Velocity" "$SERVER/plugins/floodgate"
if [[ -f "$REPO_ROOT/proxy/plugins/Geyser-Velocity/config.yml" ]]; then
  cp "$REPO_ROOT/proxy/plugins/Geyser-Velocity/config.yml" "$SERVER/plugins/Geyser-Velocity/config.yml"
fi
# Floodgate will generate its own key.pem on first start; never ship a stale one.
rm -f "$SERVER/plugins/floodgate/key.pem"

# World: copy the pristine world only on first install (never clobber live data)
if [[ ! -d "$SERVER/world" && -d "$REPO_ROOT/world" ]]; then
  say "Installing initial world"
  cp -r "$REPO_ROOT/world" "$SERVER/world"
fi

echo "eula=true" > "$SERVER/eula.txt"

# ---------------------------------------------------------------------------
# 5. Generate secrets and patch configs
# ---------------------------------------------------------------------------
say "Generating Velocity modern-forwarding secret"
if [[ ! -s "$SERVER/forwarding.secret" ]]; then
  python3 -c 'import secrets;print(secrets.token_hex(16),end="")' > "$SERVER/forwarding.secret"
fi
FS="$(cat "$SERVER/forwarding.secret")"

python3 - "$SERVER" "$FS" <<'PY'
import pathlib, re, sys
server, fs = pathlib.Path(sys.argv[1]), sys.argv[2]

# Paper backend: enable Velocity modern forwarding with the shared secret.
pg = server / "config" / "paper-global.yml"
if pg.exists():
    s = pg.read_text()
    s = re.sub(r"(?ms)^(  velocity:\n(?:    .*\n)*?    enabled: )false", r"\1true", s, count=1)
    s = re.sub(r"(?ms)^(  velocity:\n(?:    .*\n)*?    secret: )'[^']*'", r"\1'%s'" % fs, s, count=1)
    s = re.sub(r"(?ms)^(  velocity:\n(?:    .*\n)*?    secret: )''", r"\1'%s'" % fs, s, count=1)
    pg.write_text(s)
    print("patched", pg)

# Velocity proxy: modern forwarding + secret file.
vt = server / "velocity.toml"
if vt.exists():
    s = vt.read_text()
    s = re.sub(r'(?m)^player-info-forwarding-mode\s*=.*$',
               'player-info-forwarding-mode = "modern"', s)
    if 'forwarding-secret-file' not in s:
        s += '\nforwarding-secret-file = "forwarding.secret"\n'
    vt.write_text(s)
    print("patched", vt)

# Backend must sit on loopback and trust the proxy (offline mode).
sp = server / "server.properties"
if sp.exists():
    lines = sp.read_text().splitlines()
    def setkey(lines, key, val):
        for i, ln in enumerate(lines):
            if ln.startswith(key + "="):
                lines[i] = f"{key}={val}"
                return lines
        lines.append(f"{key}={val}")
        return lines
    lines = setkey(lines, "server-ip", "127.0.0.1")
    lines = setkey(lines, "server-port", "25566")
    lines = setkey(lines, "online-mode", "false")
    sp.write_text("\n".join(lines) + "\n")
    print("patched", sp)

# Geyser: listen on all interfaces, Bedrock port 19132, Floodgate auth.
gc = server / "plugins" / "Geyser-Velocity" / "config.yml"
if gc.exists():
    s = gc.read_text()
    s = re.sub(r"(?m)^(  address:\s*).*$", r"\g<1>0.0.0.0", s, count=1)
    s = re.sub(r"(?m)^(  port:\s*).*$", r"\g<1>19132", s, count=1)
    s = re.sub(r"(?m)^(  auth-type:\s*).*$", r"\g<1>floodgate", s, count=1)
    gc.write_text(s)
    print("patched", gc)
PY

# ---------------------------------------------------------------------------
# 6. playit.gg agent (permanent Bedrock tunnel)
# ---------------------------------------------------------------------------
install -m 0755 "$STAGE/playit" "$INSTALL_DIR/playit/playit"
if [[ -n "${PLAYIT_SECRET_FILE:-}" && -f "${PLAYIT_SECRET_FILE}" ]]; then
  say "Installing provided playit secret (reusing existing Bedrock address)"
  install -m 0600 "$PLAYIT_SECRET_FILE" "$INSTALL_DIR/playit/playit.toml"
elif [[ ! -s "$INSTALL_DIR/playit/playit.toml" ]]; then
  say "No playit secret supplied - a new tunnel must be claimed after install"
  printf 'secret_key = ""\n' > "$INSTALL_DIR/playit/playit.toml"
  chmod 0600 "$INSTALL_DIR/playit/playit.toml"
fi

# ---------------------------------------------------------------------------
# 7. Run wrappers + environment
# ---------------------------------------------------------------------------
say "Writing run wrappers and environment file"
cat > "$INSTALL_DIR/fb-smp.env" <<EOF
JAVA_BIN=$JAVA_BIN
PAPER_XMS=$PAPER_XMS
PAPER_XMX=$PAPER_XMX
VELOCITY_XMS=$VELOCITY_XMS
VELOCITY_XMX=$VELOCITY_XMX
# Admin panel bridge: loopback panel (8765) published on the public port.
PANEL_BRIDGE_LISTEN=0.0.0.0
PANEL_BRIDGE_PORT=12000
PANEL_BRIDGE_TARGET_HOST=127.0.0.1
PANEL_BRIDGE_TARGET_PORT=8765
# Health watchdog
FBSMP_BASE=$INSTALL_DIR
FBSMP_HEALTH_LOG=$INSTALL_DIR/health.log
FBSMP_HEALTH_STATE=$INSTALL_DIR/.fbsmp-health-state
FBSMP_PLAYIT_DIR=$INSTALL_DIR/playit
FBSMP_PLAYIT_BIN=./playit
FBSMP_PLAYIT_SECRET=./playit.toml
FBSMP_BRIDGE_DIR=$SERVER
FBSMP_BRIDGE_CMD=python3 $SERVER/tools/panel_bridge.py
FBSMP_PANEL_URL=http://127.0.0.1:12000/
FBSMP_MANAGE_PLAYIT=0
FBSMP_PLAYIT_RESTART_CMD=systemctl restart fb-smp-playit.service
EOF

mkdir -p "$SERVER/tools"
cp "$REPO_ROOT/tools/panel_bridge.py" "$SERVER/tools/panel_bridge.py"
cp "$REPO_ROOT/fbsmp-health.sh" "$INSTALL_DIR/fbsmp-health.sh"
chmod 0755 "$INSTALL_DIR/fbsmp-health.sh"

cat > "$SERVER/run-paper.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
cd "$SERVER"
source "$INSTALL_DIR/fb-smp.env"
exec "\$JAVA_BIN" -Xms"\$PAPER_XMS" -Xmx"\$PAPER_XMX" -jar paper.jar --nogui
EOF

cat > "$SERVER/run-velocity.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
cd "$SERVER"
source "$INSTALL_DIR/fb-smp.env"
exec "\$JAVA_BIN" -Xms"\$VELOCITY_XMS" -Xmx"\$VELOCITY_XMX" -jar velocity.jar
EOF

cat > "$INSTALL_DIR/playit/run-playit.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
cd "$INSTALL_DIR/playit"
exec ./playit --secret-path ./playit.toml
EOF
cat > "$INSTALL_DIR/run-panel-bridge.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
source "$INSTALL_DIR/fb-smp.env"
exec python3 "$SERVER/tools/panel_bridge.py"
EOF
cat > "$INSTALL_DIR/run-health.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
source "$INSTALL_DIR/fb-smp.env"
exec bash "$INSTALL_DIR/fbsmp-health.sh"
EOF
chmod 0755 "$SERVER/run-paper.sh" "$SERVER/run-velocity.sh" "$INSTALL_DIR/playit/run-playit.sh" \
           "$INSTALL_DIR/run-panel-bridge.sh" "$INSTALL_DIR/run-health.sh"

# ---------------------------------------------------------------------------
# 8. systemd units
# ---------------------------------------------------------------------------
say "Installing systemd services"
cat > /etc/systemd/system/fb-smp-paper.service <<EOF
[Unit]
Description=FB-SMP Paper backend
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$SERVICE_USER
WorkingDirectory=$SERVER
ExecStart=$SERVER/run-paper.sh
Restart=always
RestartSec=15
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/fb-smp-velocity.service <<EOF
[Unit]
Description=FB-SMP Velocity proxy + Geyser
After=network-online.target fb-smp-paper.service
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
User=$SERVICE_USER
WorkingDirectory=$SERVER
ExecStart=$SERVER/run-velocity.sh
Restart=always
RestartSec=15
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/fb-smp-playit.service <<EOF
[Unit]
Description=FB-SMP playit.gg tunnel agent
After=network-online.target fb-smp-velocity.service
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
User=$SERVICE_USER
WorkingDirectory=$INSTALL_DIR/playit
ExecStart=$INSTALL_DIR/playit/run-playit.sh
Restart=always
RestartSec=15

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/fb-smp-panel-bridge.service <<EOF
[Unit]
Description=FB-SMP admin panel ingress bridge
After=network-online.target fb-smp-paper.service
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
User=$SERVICE_USER
WorkingDirectory=$SERVER
ExecStart=$INSTALL_DIR/run-panel-bridge.sh
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/fb-smp-health.service <<EOF
[Unit]
Description=FB-SMP external health watchdog
After=network-online.target fb-smp-velocity.service fb-smp-playit.service
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
User=$SERVICE_USER
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/run-health.sh
Restart=always
RestartSec=30

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/fb-smp-backup.service <<EOF
[Unit]
Description=FB-SMP nightly backup

[Service]
Type=oneshot
User=$SERVICE_USER
WorkingDirectory=$REPO_ROOT
ExecStart=/usr/bin/env SERVER_DIR=$SERVER BACKUP_DIR=$SERVER/backups bash $REPO_ROOT/backup.sh
EOF

cat > /etc/systemd/system/fb-smp-backup.timer <<'EOF'
[Unit]
Description=Run FB-SMP backup nightly

[Timer]
OnCalendar=*-*-* 04:30:00
Persistent=true

[Install]
WantedBy=timers.target
EOF

chown -R "$SERVICE_USER":"$SERVICE_USER" "$INSTALL_DIR"
chmod 0600 "$SERVER/forwarding.secret" 2>/dev/null || true
chmod 0600 "$INSTALL_DIR/playit/playit.toml" 2>/dev/null || true

# ---------------------------------------------------------------------------
# 9. Enable + start
# ---------------------------------------------------------------------------
say "Enabling and starting services"
systemctl daemon-reload
systemctl enable fb-smp-paper.service fb-smp-velocity.service fb-smp-playit.service \
                 fb-smp-panel-bridge.service fb-smp-health.service
systemctl enable --now fb-smp-backup.timer
systemctl restart fb-smp-paper.service
# give Paper time to become ready before the proxy dials in
sleep 25
systemctl restart fb-smp-velocity.service
systemctl restart fb-smp-playit.service
systemctl restart fb-smp-panel-bridge.service
systemctl restart fb-smp-health.service

say "Disabling the old orchestrator scripts to avoid double starts"
chmod -x "$REPO_ROOT/start.sh" 2>/dev/null || true
chmod -x "$REPO_ROOT/stop.sh" 2>/dev/null || true

# ---------------------------------------------------------------------------
# 10. Status
# ---------------------------------------------------------------------------
say "Done. Service status:"
for s in fb-smp-paper fb-smp-velocity fb-smp-playit fb-smp-panel-bridge fb-smp-health; do
  printf '  %-24s %s\n' "$s" "$(systemctl is-active "$s" 2>/dev/null || true)"
done

cat <<EOF

Logs:
  journalctl -u fb-smp-paper -f
  journalctl -u fb-smp-velocity -f
  journalctl -u fb-smp-playit -f
  journalctl -u fb-smp-panel-bridge -f
  journalctl -u fb-smp-health -f

Admin panel: the bridge publishes the loopback panel on port 12000.
  - LAN/VPN:  http://<this-host-ip>:12000/
  - Public:   put nginx/Caddy in front of 127.0.0.1:12000 with TLS, then
              browse https://<your-domain>/
  Log in with the panel account created earlier (OWNER). Change the password
  after first login. The owner token in server/plugins/FBSMP/admin-token is a
  break-glass OWNER credential - rotate it once you are in.

Firewall: for internet play the playit.gg tunnel needs NO inbound ports.
To also allow direct LAN/direct-IP Java play, open inbound TCP 25565.
To reach the panel directly (no reverse proxy), open inbound TCP 12000 and
prefer an SSH tunnel instead:  ssh -L 12000:127.0.0.1:12000 user@host

Bedrock (playit.gg): read the tunnel address with
  journalctl -u fb-smp-playit -f
  - to claim a fresh tunnel:
      sudo -u $SERVICE_USER $INSTALL_DIR/playit/playit-cli --socket-path /tmp/p.sock setup
  - to reuse an existing address, re-run with PLAYIT_SECRET_FILE=/path/to/secret

Java:  open TCP 25565 and use <this-host-public-ip>:25565
EOF
