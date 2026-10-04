#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="${SERVER_DIR:-$ROOT/server}"
BACKUP_DIR="${BACKUP_DIR:-$SERVER_DIR/backups}"
RETENTION="${RETENTION:-20}"
DB="${FBSMP_DATABASE:-$SERVER_DIR/plugins/FBSMP/fbsmp.sqlite}"
STAMP="$(date -u +%Y%m%d-%H%M%S)"

mkdir -p "$BACKUP_DIR"
[[ -f "$DB" ]] || { printf 'Database not found: %s\n' "$DB" >&2; exit 1; }
if command -v sqlite3 >/dev/null 2>&1; then
  sqlite3 "$DB" ".timeout 5000" ".backup '$BACKUP_DIR/fbsmp-$STAMP.sqlite'"
else
  printf 'sqlite3 is required for an offline CLI backup. Use /fbsmp backup while Paper is running instead.\n' >&2
  exit 1
fi
tar -czf "$BACKUP_DIR/fbsmp-config-$STAMP.tar.gz" \
  "$SERVER_DIR/server.properties" "$SERVER_DIR/velocity.toml" \
  "$ROOT/fbsmp-paper/src/main/resources" 2>/dev/null
find "$BACKUP_DIR" -maxdepth 1 -name 'fbsmp-*.sqlite' -type f -printf '%T@ %p\n' \
  | sort -nr | awk -v keep="$RETENTION" 'NR > keep {print $2}' | xargs -r rm -f --
printf 'Backup complete: %s\n' "$BACKUP_DIR/fbsmp-$STAMP.sqlite"