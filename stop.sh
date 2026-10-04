#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="${SERVER_DIR:-$ROOT/server}"

stop_pidfile() {
  local name="$1"
  local file="$SERVER_DIR/$2"
  [[ -f "$file" ]] || return 0
  local pid
  pid="$(cat "$file")"
  if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
    kill -TERM "$pid"
    for _ in $(seq 1 30); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
      printf 'Warning: %s did not stop within 30 seconds; leaving it running to avoid abrupt data loss.\n' "$name" >&2
      return 1
    fi
    printf '%s stopped.\n' "$name"
  fi
  rm -f "$file"
}

stop_pidfile "Velocity" "fbsmp-velocity.pid"
stop_pidfile "Paper" "fbsmp-paper.pid"