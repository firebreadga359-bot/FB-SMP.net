#!/usr/bin/env bash
# FB-SMP named panel tunnel -> https://panel.fb-smp.net
#
# Runs the Cloudflare named tunnel that publishes the loopback admin panel on a
# stable hostname (the ingress and DNS record live in Cloudflare, not here).
# Needs the tunnel credentials file on the host; never commit it. The keeper
# calls this, so the tunnel returns automatically if it dies.
set -u
BASE="${FBSMP_BASE:-/workspace}"
TID="${FBSMP_CF_TUNNEL_ID:-65d123e6-0322-41e1-af00-f0582ca794cd}"
CRED="${FBSMP_CF_CREDENTIALS:-$BASE/cf-credentials.json}"
LOG="${FBSMP_NAMED_TUNNEL_LOG:-$BASE/cf-named.log}"
PATTERN="cloudflared tunnel --credentials-file"

[ -x "$BASE/cloudflared" ] || exit 0
[ -f "$CRED" ] || { echo "$(date -u +%FT%TZ) no credentials at $CRED" >>"$LOG"; exit 0; }

if ! pgrep -f "$PATTERN" >/dev/null 2>&1; then
  ( cd "$BASE" && setsid nohup "$BASE/cloudflared" tunnel --no-autoupdate \
      --credentials-file "$CRED" run "$TID" >>"$LOG" 2>&1 </dev/null & )
  sleep 6
fi
exit 0
