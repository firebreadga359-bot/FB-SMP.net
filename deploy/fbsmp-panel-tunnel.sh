#!/usr/bin/env bash
# FB-SMP public panel tunnel.
#
# The admin panel stays bound to loopback; this exposes it to the internet
# through a Cloudflare quick tunnel so the control room is reachable from
# anywhere. The keeper and the keep-alive workflow both call this, so the
# tunnel comes back automatically if it dies.
set -u
BASE="${FBSMP_BASE:-/workspace}"
LOG="${FBSMP_PANEL_TUNNEL_LOG:-$BASE/cf-panel.log}"
URLFILE="${FBSMP_PANEL_URL_FILE:-$BASE/panel-url.txt}"
TARGET="http://127.0.0.1:12000"
PATTERN="cloudflared tunnel --url $TARGET"

if ! pgrep -f "$PATTERN" >/dev/null 2>&1; then
  : >"$LOG"
  ( cd "$BASE" && setsid nohup "$BASE/cloudflared" tunnel --url "$TARGET" --no-autoupdate >>"$LOG" 2>&1 </dev/null & )
  for _ in $(seq 1 15); do
    grep -qE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOG" 2>/dev/null && break
    sleep 2
  done
fi

u="$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOG" 2>/dev/null | head -1)"
[ -n "$u" ] && echo "$u" >"$URLFILE"
exit 0
