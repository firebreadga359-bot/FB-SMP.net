#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="${SERVER_DIR:-$ROOT/server}"
JAVA_BIN="${JAVA25_HOME:+$JAVA25_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
PAPER_JAR="${PAPER_JAR:-$SERVER_DIR/paper.jar}"
VELOCITY_JAR="${VELOCITY_JAR:-$SERVER_DIR/velocity.jar}"
PAPER_LOG="${PAPER_LOG:-$SERVER_DIR/logs/fbsmp-paper-startup.log}"
VELOCITY_LOG="${VELOCITY_LOG:-$SERVER_DIR/logs/fbsmp-velocity.log}"
READY_TIMEOUT="${READY_TIMEOUT:-180}"

fail() {
  printf 'FB-SMP startup error: %s\n' "$*" >&2
  exit 1
}

[[ -n "$JAVA_BIN" && -x "$JAVA_BIN" ]] || fail "Java was not found. Set JAVA25_HOME to a Java 25 installation."
JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -n1)"
[[ "$JAVA_MAJOR" == "25" ]] || fail "Java 25 is required; selected runtime reports major version '${JAVA_MAJOR:-unknown}'. Set JAVA25_HOME explicitly."
[[ -f "$PAPER_JAR" ]] || fail "Paper jar not found: $PAPER_JAR"
[[ -f "$VELOCITY_JAR" ]] || fail "Velocity jar not found: $VELOCITY_JAR"

mkdir -p "$SERVER_DIR/logs" "$SERVER_DIR/backups" "$SERVER_DIR/plugins"
[[ -f "$SERVER_DIR/server.properties" ]] || fail "Missing server.properties. Restore the supplied localhost-only backend settings."
grep -Eq '^server-ip=127\.0\.0\.1$' "$SERVER_DIR/server.properties" || fail "Paper must bind to 127.0.0.1 behind Velocity."
grep -Eq '^server-port=25566$' "$SERVER_DIR/server.properties" || fail "Paper backend must use port 25566."

cd "$SERVER_DIR"
if [[ -f "$SERVER_DIR/fbsmp-paper.pid" ]] && kill -0 "$(cat "$SERVER_DIR/fbsmp-paper.pid")" 2>/dev/null; then
  fail "Paper already appears to be running (PID $(cat "$SERVER_DIR/fbsmp-paper.pid"))."
fi

printf 'Starting Paper with Java %s...\n' "$JAVA_MAJOR"
nohup "$JAVA_BIN" -Xms2G -Xmx4G -jar "$PAPER_JAR" --nogui >>"$PAPER_LOG" 2>&1 &
PAPER_PID=$!
printf '%s\n' "$PAPER_PID" > "$SERVER_DIR/fbsmp-paper.pid"

elapsed=0
until grep -qE 'Done \([0-9.]+s\)!|Done \([0-9.]+s\)' "$PAPER_LOG"; do
  if ! kill -0 "$PAPER_PID" 2>/dev/null; then
    fail "Paper exited during startup. Inspect $PAPER_LOG."
  fi
  if (( elapsed >= READY_TIMEOUT )); then
    kill "$PAPER_PID" 2>/dev/null || true
    fail "Paper did not become ready within ${READY_TIMEOUT}s. Inspect $PAPER_LOG."
  fi
  sleep 2
  elapsed=$((elapsed + 2))
done

if [[ -f "$SERVER_DIR/fbsmp-velocity.pid" ]] && kill -0 "$(cat "$SERVER_DIR/fbsmp-velocity.pid")" 2>/dev/null; then
  fail "Velocity already appears to be running (PID $(cat "$SERVER_DIR/fbsmp-velocity.pid"))."
fi
printf 'Paper is ready. Starting Velocity...\n'
nohup "$JAVA_BIN" -Xms512M -Xmx1G -jar "$VELOCITY_JAR" >>"$VELOCITY_LOG" 2>&1 &
printf '%s\n' "$!" > "$SERVER_DIR/fbsmp-velocity.pid"
printf 'FB-SMP started. Paper log: %s; Velocity log: %s\n' "$PAPER_LOG" "$VELOCITY_LOG"