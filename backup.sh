#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="${SERVER_DIR:-$ROOT/server}"
BACKUP_DIR="${BACKUP_DIR:-$SERVER_DIR/backups}"
RETENTION="${RETENTION:-20}"
DB="${FBSMP_DATABASE:-$SERVER_DIR/plugins/FBSMP/fbsmp.sqlite}"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
DB_BACKUP="$BACKUP_DIR/fbsmp-$STAMP.sqlite"
CONFIG_BACKUP="$BACKUP_DIR/fbsmp-config-$STAMP.tar.gz"

mkdir -p "$BACKUP_DIR"
[[ -f "$DB" ]] || { printf 'Database not found: %s\n' "$DB" >&2; exit 1; }
if command -v sqlite3 >/dev/null 2>&1; then
  DB_BACKUP_SQL="${DB_BACKUP//\'/\'\'}"
  sqlite3 "$DB" ".timeout 5000" ".backup '$DB_BACKUP_SQL'"
else
  printf 'sqlite3 is required for an offline CLI backup. Use /fbsmp backup while Paper is running instead.\n' >&2
  exit 1
fi

CONFIG_PATHS=()
for relative_path in server.properties velocity.toml paper-global.yml config/paper-global.yml; do
  [[ -f "$SERVER_DIR/$relative_path" ]] && CONFIG_PATHS+=("$relative_path")
done
if [[ -d "$SERVER_DIR/plugins/FBSMP" ]]; then
  shopt -s nullglob
  for config_file in "$SERVER_DIR"/plugins/FBSMP/*.yml; do
    CONFIG_PATHS+=("${config_file#"$SERVER_DIR"/}")
  done
  shopt -u nullglob
fi
if ((${#CONFIG_PATHS[@]})); then
  tar -C "$SERVER_DIR" -czf "$CONFIG_BACKUP" "${CONFIG_PATHS[@]}"
else
  tar -C "$ROOT" -czf "$CONFIG_BACKUP" fbsmp-paper/src/main/resources
fi
find "$BACKUP_DIR" -maxdepth 1 -name 'fbsmp-*.sqlite' -type f -printf '%T@ %p\n' \
  | sort -nr | awk -v keep="$RETENTION" 'NR > keep {print $2}' | xargs -r rm -f --
find "$BACKUP_DIR" -maxdepth 1 -name 'fbsmp-config-*.tar.gz' -type f -printf '%T@ %p\n' \
  | sort -nr | awk -v keep="$RETENTION" 'NR > keep {print $2}' | xargs -r rm -f --
printf 'Database backup: %s\nConfiguration backup: %s\n' "$DB_BACKUP" "$CONFIG_BACKUP"