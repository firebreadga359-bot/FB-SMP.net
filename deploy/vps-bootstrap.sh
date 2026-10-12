#!/usr/bin/env bash
#
# FB-SMP one-shot VPS bootstrap
# -----------------------------
# Runs the ENTIRE server-side setup on a fresh Debian/Ubuntu box in one command:
# install prerequisites, clone/update the repo, install the systemd stack,
# restore your existing world from an encrypted bundle, arm Discord alerts, and
# verify. Safe to re-run: the world and secrets are only restored when a bundle
# is supplied, and are otherwise left untouched.
#
# Usage (all inputs optional except the ones you need):
#
#   sudo FBSMP_BUNDLE=/root/fbsmp-migrate.tar.gz.enc \
#        FBSMP_BUNDLE_PASS_FILE=/root/migrate.pass \
#        FBSMP_ALERT_WEBHOOK_FILE=/root/webhook.txt \
#        bash deploy/vps-bootstrap.sh
#
# Environment:
#   FBSMP_BRANCH                branch to deploy            (default main)
#   FBSMP_REPO_URL              clone URL                   (default the repo)
#   FBSMP_REPO_DIR              checkout path               (default /opt/fb-smp-repo)
#   FBSMP_BUNDLE                encrypted migration bundle  (optional)
#   FBSMP_BUNDLE_PASS_FILE      file holding the passphrase (optional)
#   FBSMP_BUNDLE_PASS           passphrase inline           (optional, less safe)
#   FBSMP_ALERT_WEBHOOK         Discord webhook URL         (optional)
#   FBSMP_ALERT_WEBHOOK_FILE    file holding the webhook    (optional)
#
set -euo pipefail

[[ "$(id -u)" -eq 0 ]] || { echo "run as root (sudo bash deploy/vps-bootstrap.sh)" >&2; exit 1; }

REPO_URL="${FBSMP_REPO_URL:-https://github.com/firebreadga359-bot/FB-SMP.net.git}"
REPO_DIR="${FBSMP_REPO_DIR:-/opt/fb-smp-repo}"
BRANCH="${FBSMP_BRANCH:-main}"
INSTALL_DIR="${INSTALL_DIR:-/opt/fb-smp}"
SERVER="$INSTALL_DIR/server"
SERVICES="fb-smp-paper fb-smp-velocity fb-smp-playit fb-smp-panel-bridge fb-smp-health"

say() { printf '\n==> %s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

export DEBIAN_FRONTEND=noninteractive

# --- 1. Prerequisites ------------------------------------------------------
say "Installing prerequisites"
apt-get update -y
apt-get install -y git ca-certificates curl openssl

# --- 2. Clone or update the repository -------------------------------------
if [[ -d "$REPO_DIR/.git" ]]; then
  say "Updating checkout at $REPO_DIR"
  git -C "$REPO_DIR" fetch --all --prune
  git -C "$REPO_DIR" checkout -f -B "$BRANCH" "origin/$BRANCH"
  git -C "$REPO_DIR" reset --hard "origin/$BRANCH"
else
  say "Cloning $REPO_URL ($BRANCH) to $REPO_DIR"
  mkdir -p "$(dirname "$REPO_DIR")"
  git clone --branch "$BRANCH" "$REPO_URL" "$REPO_DIR"
fi

# --- 3. Install the stack --------------------------------------------------
say "Installing the systemd stack"
FBSMP_BRANCH="$BRANCH" bash "$REPO_DIR/deploy/deploy.sh"

# --- 4. Restore the world from an encrypted bundle (optional) --------------
if [[ -n "${FBSMP_BUNDLE:-}" ]]; then
  [[ -f "$FBSMP_BUNDLE" ]] || die "bundle not found: $FBSMP_BUNDLE"

  PASS_ARG=()
  if [[ -n "${FBSMP_BUNDLE_PASS_FILE:-}" ]]; then
    [[ -f "$FBSMP_BUNDLE_PASS_FILE" ]] || die "passphrase file not found: $FBSMP_BUNDLE_PASS_FILE"
    PASS_ARG=(-pass "file:$FBSMP_BUNDLE_PASS_FILE")
  elif [[ -n "${FBSMP_BUNDLE_PASS:-}" ]]; then
    PASS_ARG=(-pass "pass:$FBSMP_BUNDLE_PASS")
  else
    die "set FBSMP_BUNDLE_PASS_FILE or FBSMP_BUNDLE_PASS to decrypt the bundle"
  fi

  say "Stopping services before restoring the world"
  # shellcheck disable=SC2086
  systemctl stop $SERVICES || true

  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
  say "Decrypting bundle"
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 "${PASS_ARG[@]}" \
    -in "$FBSMP_BUNDLE" -out "$WORK/bundle.tar.gz"
  mkdir -p "$WORK/unpacked"
  tar -xzf "$WORK/bundle.tar.gz" -C "$WORK/unpacked"

  say "Restoring world, plugin data and secrets into $SERVER"
  # World and server config (overwrite what the installer created).
  if [[ -d "$WORK/unpacked/world" ]]; then
    rm -rf "$SERVER/world"
    cp -a "$WORK/unpacked/world" "$SERVER/"
  fi
  [[ -f "$WORK/unpacked/server.properties" ]] && cp -a "$WORK/unpacked/server.properties" "$SERVER/"
  [[ -f "$WORK/unpacked/forwarding.secret" ]] && cp -a "$WORK/unpacked/forwarding.secret" "$SERVER/"
  [[ -f "$WORK/unpacked/config/paper-global.yml" ]] \
    && cp -a "$WORK/unpacked/config/paper-global.yml" "$SERVER/config/paper-global.yml"
  # Plugin DATA only - never replace the plugins dir, or the Geyser/Via jars
  # the installer placed there would be deleted.
  mkdir -p "$SERVER/plugins"
  for d in FBSMP floodgate; do
    if [[ -d "$WORK/unpacked/plugins/$d" ]]; then
      rm -rf "$SERVER/plugins/$d"
      cp -a "$WORK/unpacked/plugins/$d" "$SERVER/plugins/"
    fi
  done

  if [[ -f "$WORK/unpacked/playit/playit.toml" ]]; then
    say "Installing playit secret (keeps the same public addresses)"
    install -m 0600 "$WORK/unpacked/playit/playit.toml" "$INSTALL_DIR/playit/playit.toml"
  fi

  rm -rf "$WORK/unpacked/plugins/FBSMP/"*.sqlite-shm "$WORK/unpacked/plugins/FBSMP/"*.sqlite-wal 2>/dev/null || true
  chown -R "${SERVICE_USER:-fbsmp}:${SERVICE_USER:-fbsmp}" "$SERVER" "$INSTALL_DIR/playit"
  rm -rf "$WORK"

  say "Starting services with the restored world"
  systemctl start fb-smp-paper
  sleep 25
  # shellcheck disable=SC2086
  systemctl start $SERVICES
fi

# --- 5. Arm Discord alerts (optional) --------------------------------------
WEBHOOK="${FBSMP_ALERT_WEBHOOK:-}"
[[ -z "$WEBHOOK" && -n "${FBSMP_ALERT_WEBHOOK_FILE:-}" && -f "${FBSMP_ALERT_WEBHOOK_FILE:-}" ]] \
  && WEBHOOK="$(cat "$FBSMP_ALERT_WEBHOOK_FILE")"
if [[ -n "$WEBHOOK" ]]; then
  say "Arming Discord outage alerts"
  printf '%s' "$WEBHOOK" > "$INSTALL_DIR/.fbsmp-alert-webhook"
  chmod 600 "$INSTALL_DIR/.fbsmp-alert-webhook"
  chown "${SERVICE_USER:-fbsmp}:${SERVICE_USER:-fbsmp}" "$INSTALL_DIR/.fbsmp-alert-webhook"
  systemctl restart fb-smp-health
fi

# --- 6. Status + verification ----------------------------------------------
say "Service status"
systemctl --no-pager --full status $SERVICES 2>/dev/null | grep -E '^\s*(●|Loaded:|Active:)' || true

say "Waiting for the public addresses to come up"
sleep 10
for addr in "cairo-paste.tun.ply.gg:25565:java" "fried-toilette.tun.ply.gg:37298:bedrock"; do
  host="${addr%%:*}"; rest="${addr#*:}"; port="${rest%%:*}"; ep="${rest#*:}"
  result="$(curl -s --max-time 20 -A 'FB-SMP-bootstrap/1.0' \
    "https://api.mcstatus.io/v2/status/$ep/$host:$port" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print("online" if d.get("online") else "offline")' 2>/dev/null || echo "unknown")"
  printf '  %-45s %s\n' "$host:$port ($ep)" "$result"
done

cat <<EOF

==> Bootstrap complete.

Manage the stack:
  systemctl status ${SERVICES// / }
Admin panel (loopback only, reach over SSH tunnel):
  ssh -L 12000:127.0.0.1:12000 root@<this-host>   then open http://127.0.0.1:12000/

To enable auto-deploy on every push to $BRANCH, add these GitHub repository
secrets (Settings -> Secrets and variables -> Actions): VPS_HOST, VPS_USER,
VPS_SSH_KEY (optional VPS_PORT, VPS_REPO_DIR=$REPO_DIR).
EOF
