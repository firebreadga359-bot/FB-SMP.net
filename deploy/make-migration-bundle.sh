#!/usr/bin/env bash
#
# FB-SMP migration bundle
# -----------------------
# Snapshot a running server's world and plugin data into a tarball that can be
# unpacked straight into /opt/fb-smp/server on the target VPS.
#
#     sudo bash deploy/make-migration-bundle.sh [SRC_SERVER_DIR] [OUT_TARBALL]
#
# Defaults: SRC_SERVER_DIR=/workspace/fb-smp/server  OUT=/workspace/fbsmp-migrate.tar.gz
#
# IMPORTANT: stop the source server first so the world and SQLite database are
# consistent. The script refuses to run if a paper.jar process is alive.
#
set -euo pipefail

SRC="${1:-/workspace/fb-smp/server}"
OUT="${2:-/workspace/fbsmp-migrate.tar.gz}"

say() { printf '\n==> %s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

[[ -d "$SRC" ]] || die "source server dir not found: $SRC"
[[ -d "$SRC/world" ]] || die "no world at $SRC/world"

if pgrep -f "paper\.jar" >/dev/null 2>&1; then
  die "a paper.jar process is running - stop the server first for a consistent snapshot"
fi

# Flush any WAL into the main DB so a single .sqlite file is complete.
if command -v sqlite3 >/dev/null 2>&1 && [[ -f "$SRC/plugins/FBSMP/fbsmp.sqlite" ]]; then
  sqlite3 "$SRC/plugins/FBSMP/fbsmp.sqlite" 'PRAGMA wal_checkpoint(TRUNCATE);' >/dev/null 2>&1 || true
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
say "Staging into $STAGE"

# --- World (level-name may differ; read it from server.properties) ----------
LEVEL_NAME="world"
if [[ -f "$SRC/server.properties" ]]; then
  LEVEL_NAME="$(grep -E '^level-name=' "$SRC/server.properties" | cut -d= -f2- || echo world)"
  LEVEL_NAME="${LEVEL_NAME:-world}"
fi
say "Copying world '$LEVEL_NAME'"
cp -a "$SRC/$LEVEL_NAME" "$STAGE/$LEVEL_NAME"
# Never ship a live session lock.
rm -f "$STAGE/$LEVEL_NAME/session.lock"

# --- Plugin data -----------------------------------------------------------
say "Copying plugin data"
mkdir -p "$STAGE/plugins"
[[ -d "$SRC/plugins/FBSMP" ]] && cp -a "$SRC/plugins/FBSMP" "$STAGE/plugins/FBSMP"
# Bedrock player identities are tied to the Floodgate key; keep it.
if [[ -d "$SRC/plugins/floodgate" ]]; then
  mkdir -p "$STAGE/plugins/floodgate"
  [[ -f "$SRC/plugins/floodgate/key.pem" ]] && cp -a "$SRC/plugins/floodgate/key.pem" "$STAGE/plugins/floodgate/"
  [[ -f "$SRC/plugins/floodgate/config.yml" ]] && cp -a "$SRC/plugins/floodgate/config.yml" "$STAGE/plugins/floodgate/"
fi

# --- Secrets and server config --------------------------------------------
say "Copying forwarding secret and server config"
[[ -f "$SRC/forwarding.secret" ]] && cp -a "$SRC/forwarding.secret" "$STAGE/"
[[ -f "$SRC/server.properties" ]] && cp -a "$SRC/server.properties" "$STAGE/"
[[ -f "$SRC/config/paper-global.yml" ]] && { mkdir -p "$STAGE/config"; cp -a "$SRC/config/paper-global.yml" "$STAGE/config/"; }

# --- playit.gg secret (keeps the same public Java + Bedrock addresses) -----
PLAYIT_TOML="${PLAYIT_SECRET_FILE:-/workspace/playit-agent/playit.toml}"
if [[ -f "$PLAYIT_TOML" ]]; then
  say "Including playit secret ($PLAYIT_TOML)"
  mkdir -p "$STAGE/playit"
  cp -a "$PLAYIT_TOML" "$STAGE/playit/playit.toml"
else
  say "No playit secret found at $PLAYIT_TOML - a new tunnel must be claimed"
fi

# Drop transient SQLite sidecars and plugin caches from the snapshot.
rm -f "$STAGE/plugins/FBSMP/"*.sqlite-wal "$STAGE/plugins/FBSMP/"*.sqlite-shm 2>/dev/null || true

say "Writing $OUT"
tar -czf "$OUT" -C "$STAGE" .

say "Bundle ready: $OUT ($(du -h "$OUT" | cut -f1))"
echo
echo "Contents:"
tar -tzf "$OUT" | grep -vE '/$' | sed 's/^/  /' | head -40
