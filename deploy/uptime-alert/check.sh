#!/usr/bin/env bash
# FB-SMP uptime alert.
#
# Checks the public Java and Bedrock addresses (and the admin panel when a URL
# is provided) from a third party. On a transition to down it opens a GitHub
# issue labelled "uptime"; when everything is healthy again it comments and
# closes that issue. Deduplicated through the issue itself, so a prolonged
# outage is one issue, not one per run.
#
# Env:
#   JAVA_ADDR, BEDROCK_ADDR   host:port to check
#   PANEL_URL                 full URL to check; empty skips the panel
#   GITHUB_REPOSITORY         owner/repo (set by Actions)
#   GH_TOKEN                  token with issues:write
set -u

JAVA="${JAVA_ADDR:-cairo-paste.tun.ply.gg:25565}"
BEDROCK="${BEDROCK_ADDR:-fried-toilette.tun.ply.gg:37298}"
PANEL="${PANEL_URL:-}"
REPO="${GITHUB_REPOSITORY:-}"
LABEL="uptime"
TITLE="FB-SMP outage"
NOW="$(date -u +%FT%TZ)"

mc_up() { curl -s --max-time 15 "https://api.mcstatus.io/v2/status/$1/$2" 2>/dev/null | grep -q '"online":true'; }
panel_up() {
  [ -z "$PANEL" ] && return 0
  curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$PANEL" 2>/dev/null | grep -q 200
}

j=0; mc_up java "$JAVA" && j=1
b=0; mc_up bedrock "$BEDROCK" && b=1
p=0; panel_up && p=1

existing="$(gh issue list --repo "$REPO" --label "$LABEL" --state open --json number -q '.[0].number' 2>/dev/null || true)"

if [ "$j" = 1 ] && [ "$b" = 1 ] && [ "$p" = 1 ]; then
  if [ -n "$existing" ]; then
    gh issue comment "$existing" --repo "$REPO" --body "Recovered at $NOW. Java, Bedrock${PANEL:+, and the panel} are all answering."
    gh issue close "$existing" --repo "$REPO"
    echo "recovered; closed #$existing"
  else
    echo "all endpoints healthy"
  fi
  exit 0
fi

down=""
[ "$j" = 0 ] && down="$down
- Java ($JAVA) is DOWN"
[ "$b" = 0 ] && down="$down
- Bedrock ($BEDROCK) is DOWN"
[ "$p" = 0 ] && down="$down
- Panel ($PANEL) is DOWN"

if [ -n "$existing" ]; then
  echo "still down; alert issue #$existing already open"
  exit 0
fi

gh issue create --repo "$REPO" --title "$TITLE" --label "$LABEL" \
  --body "Detected at $NOW.$down

This issue closes automatically when every endpoint answers again."
echo "opened alert issue"
