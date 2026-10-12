#!/usr/bin/env bash
# FB-SMP external health watchdog.
#
# supervise.sh (or systemd) only checks that processes exist. A playit tunnel
# can be "up" as a process yet still be unreachable publicly: playit's API may
# assign the agent a tunnel edge this host cannot route to. This watchdog checks
# the public addresses from a third party and restarts the playit agent when
# they stop answering, and it revives the admin panel bridge.
#
# Paths default to the OpenHands container layout; a VPS install overrides them
# through the environment (see deploy/fb-smp.env).
set -uo pipefail

BASE="${FBSMP_BASE:-/workspace}"
LOG="${FBSMP_HEALTH_LOG:-$BASE/health.log}"
STATE="${FBSMP_HEALTH_STATE:-$BASE/.fbsmp-health-state}"
JAVA_HOST="${FBSMP_JAVA_ADDR:-cairo-paste.tun.ply.gg:25565}"
BEDROCK_HOST="${FBSMP_BEDROCK_ADDR:-fried-toilette.tun.ply.gg:37298}"
PANEL_URL="${FBSMP_PANEL_URL:-https://work-1-iauqtpbjyihltdtb.prod-runtime.all-hands.dev/}"
PLAYIT_DIR="${FBSMP_PLAYIT_DIR:-$BASE/playit-agent}"
PLAYIT_BIN="${FBSMP_PLAYIT_BIN:-./playit}"
PLAYIT_SECRET="${FBSMP_PLAYIT_SECRET:-./playit.toml}"
BRIDGE_DIR="${FBSMP_BRIDGE_DIR:-$BASE/fb-smp}"
BRIDGE_CMD="${FBSMP_BRIDGE_CMD:-python3 tools/panel_bridge.py}"
SUPERVISE="${FBSMP_SUPERVISE:-}"
LOCK="${FBSMP_HEALTH_LOCK:-$BASE/.fbsmp-health.lock}"
INTERVAL="${FBSMP_HEALTH_INTERVAL:-60}"
# playit handling: on a bare container the watchdog starts/restarts the agent
# itself; under systemd it must not start a duplicate, so it restarts the unit.
MANAGE_PLAYIT="${FBSMP_MANAGE_PLAYIT:-1}"
PLAYIT_ENSURE="${FBSMP_PLAYIT_ENSURE:-1}"
PLAYIT_RESTART_CMD="${FBSMP_PLAYIT_RESTART_CMD:-}"
# Optional webhook alerting (Discord/Slack/any). Set the URL in the environment
# or drop it in a file so it never has to sit in a process listing.
ALERT_WEBHOOK="${FBSMP_ALERT_WEBHOOK:-}"
ALERT_WEBHOOK_FILE="${FBSMP_ALERT_WEBHOOK_FILE:-$BASE/.fbsmp-alert-webhook}"
[[ -z "$ALERT_WEBHOOK" && -f "$ALERT_WEBHOOK_FILE" ]] && ALERT_WEBHOOK="$(cat "$ALERT_WEBHOOK_FILE")"

log() { echo "$(date -u +%FT%TZ) $*" >>"$LOG"; }
st_get() { grep -E "^$1=" "$STATE" 2>/dev/null | tail -1 | cut -d= -f2; }
st_set() {
  grep -vE "^$1=" "$STATE" >"$STATE.tmp" 2>/dev/null
  echo "$1=$2" >>"$STATE.tmp"
  mv "$STATE.tmp" "$STATE"
}

ext_up() { # $1=java|bedrock  $2=host:port
  curl -s --max-time 15 "https://api.mcstatus.io/v2/status/$1/$2" 2>/dev/null | grep -q '"online":true'
}
panel_up() { curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$PANEL_URL" 2>/dev/null | grep -q 200; }
local_up() { timeout 5 bash -c "exec 3<>/dev/tcp/127.0.0.1/$1" 2>/dev/null; }

start_playit() {
  ( cd "$PLAYIT_DIR" && rm -f playit.sock \
    && nohup "$PLAYIT_BIN" --socket-path ./playit.sock --secret-path "$PLAYIT_SECRET" >>daemon.log 2>&1 & )
}
ensure_playit() {
  [[ "$MANAGE_PLAYIT" == 1 && "$PLAYIT_ENSURE" == 1 ]] || return 0
  pgrep -f "playit --socket-path" >/dev/null 2>&1 && return 0
  log "playit process missing -> starting"
  start_playit
}
ensure_bridge() {
  pgrep -f "panel_bridge.py" >/dev/null 2>&1 && return 0
  log "panel bridge missing -> starting"
  ( cd "$BRIDGE_DIR" && nohup $BRIDGE_CMD >>"$BASE/panel-bridge.log" 2>&1 & )
}
restart_playit() {
  [[ "$MANAGE_PLAYIT" == 1 ]] || return 0
  log "restarting playit agent (tunnel unreachable)"
  if [[ -n "$PLAYIT_RESTART_CMD" ]]; then
    $PLAYIT_RESTART_CMD >>"$LOG" 2>&1
  else
    pkill -f "playit --socket-path" 2>/dev/null
    sleep 2
    start_playit
  fi
  st_set cooldown_until $(( $(date +%s) + 150 ))
  st_set java_fail 0
  st_set bedrock_fail 0
}

alert() { # $1 = message
  [[ -n "$ALERT_WEBHOOK" ]] || return 0
  # Discord: content + strict allowed_mentions (never ping @everyone/roles).
  # Slack/generic collectors read "text".
  payload=$(MSG="$1" python3 -c '
import json, os
m = os.environ["MSG"]
print(json.dumps({"content": m, "text": m, "allowed_mentions": {"parse": []}}))
')
  curl -s --max-time 15 -X POST -H "Content-Type: application/json" \
    -d "$payload" "$ALERT_WEBHOOK" >/dev/null 2>&1
  log "alert sent: $1"
}

exec 8>"$LOCK"
if ! flock -n 8; then
  log "another health watchdog already holds the lock; exiting"
  exit 0
fi

if [[ "${1:-}" == "--once" ]]; then
  ensure_playit; ensure_bridge
  log "once: java=$(ext_up java "$JAVA_HOST" && echo ok || echo DOWN) bedrock=$(ext_up bedrock "$BEDROCK_HOST" && echo ok || echo DOWN) panel=$(panel_up && echo ok || echo DOWN)"
  exit 0
fi

log "health watchdog started (pid $$)"
while true; do
  ensure_playit
  ensure_bridge

  java_ok=$(ext_up java "$JAVA_HOST" && echo 1 || echo 0)
  bedrock_ok=$(ext_up bedrock "$BEDROCK_HOST" && echo 1 || echo 0)
  panel_ok=$(panel_up && echo 1 || echo 0)
  local_ok=$(local_up 25565 && echo 1 || echo 0)

  now=$(date +%s)
  cooldown=$(st_get cooldown_until); cooldown=${cooldown:-0}

  [[ "$panel_ok" == 0 ]] && { log "panel DOWN -> ensuring bridge"; ensure_bridge; }

  if [[ "$java_ok" == 1 ]]; then st_set java_fail 0; else st_set java_fail $(( $(st_get java_fail || echo 0) + 1 )); fi
  if [[ "$bedrock_ok" == 1 ]]; then st_set bedrock_fail 0; else st_set bedrock_fail $(( $(st_get bedrock_fail || echo 0) + 1 )); fi

  # De-duplicated outage alerts: one on the way down, one on the way back up.
  now_up=$([[ "$java_ok" == 1 && "$bedrock_ok" == 1 ]] && echo 1 || echo 0)
  prev_up=$(st_get up); prev_up=${prev_up:-}
  if [[ -n "$ALERT_WEBHOOK" ]]; then
    if [[ "$now_up" == 0 && "$prev_up" != 0 ]]; then
      down=""
      [[ "$java_ok" == 0 ]] && down="$down Java($JAVA_HOST)"
      [[ "$bedrock_ok" == 0 ]] && down="$down Bedrock($BEDROCK_HOST)"
      alert "FB-SMP is DOWN:$down unreachable from a third-party check."
    elif [[ "$now_up" == 1 && "$prev_up" == 0 ]]; then
      alert "FB-SMP is back online. Java $JAVA_HOST | Bedrock $BEDROCK_HOST"
    fi
  fi
  st_set up "$now_up"

  if [[ "$now" -ge "$cooldown" ]]; then
    # Three consecutive public failures (~3 min) while the local server answers
    # means the tunnel is stuck, not the game server.
    if [[ "$local_ok" == 1 && "$java_ok" == 0 && "$(st_get java_fail || echo 0)" -ge 3 ]]; then
      restart_playit
    elif [[ "$local_ok" == 1 && "$bedrock_ok" == 0 && "$(st_get bedrock_fail || echo 0)" -ge 3 ]]; then
      restart_playit
    elif [[ "$local_ok" == 0 && "$java_ok" == 0 ]]; then
      if [[ -n "$SUPERVISE" && -f "$SUPERVISE" ]]; then
        log "local server down -> delegate to supervisor"
        bash "$SUPERVISE" --once >>"$LOG" 2>&1
      else
        log "local server down -> no supervisor configured (systemd should restart it)"
      fi
    fi
  fi

  sleep "$INTERVAL"
done
