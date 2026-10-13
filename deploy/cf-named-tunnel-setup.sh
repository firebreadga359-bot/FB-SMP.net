#!/usr/bin/env bash
# Recreate the Cloudflare named-tunnel credentials on this host.
#
# A codespace is ephemeral: a rebuild wipes cf-credentials.json. Given a
# Cloudflare API token (Tunnel:Edit, Zone:DNS:Edit) this fetches the tunnel
# token and writes the credentials file cloudflared needs. Idempotent - it
# reuses the existing tunnel and DNS record when they already exist.
#
# Env: CF_API_TOKEN, CF_ACCOUNT_ID, CF_ZONE_ID, CF_HOSTNAME (default below)
set -u

API="https://api.cloudflare.com/client/v4"
TOKEN="${CF_API_TOKEN:?set CF_API_TOKEN}"
ACC="${CF_ACCOUNT_ID:?set CF_ACCOUNT_ID}"
ZONE="${CF_ZONE_ID:?set CF_ZONE_ID}"
HOST="${CF_HOSTNAME:-panel.fb-smp.net}"
NAME="${CF_TUNNEL_NAME:-fbsmp-panel}"
BASE="${FBSMP_BASE:-/workspace}"
CRED="${FBSMP_CF_CREDENTIALS:-$BASE/cf-credentials.json}"

api() { curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "$@"; }

tid="$(api "$API/accounts/$ACC/cfd_tunnel?name=$NAME&is_deleted=false" \
  | python3 -c "import sys,json;r=json.load(sys.stdin).get('result') or [];print(r[0]['id'] if r else '')")"
if [ -z "$tid" ]; then
  tid="$(api -X POST "$API/accounts/$ACC/cfd_tunnel" \
    -d "{\"name\":\"$NAME\",\"config_src\":\"cloudflare\"}" \
    | python3 -c "import sys,json;print((json.load(sys.stdin).get('result') or {}).get('id',''))")"
fi
[ -n "$tid" ] || { echo "could not resolve tunnel id" >&2; exit 1; }

api -X PUT "$API/accounts/$ACC/cfd_tunnel/$tid/configurations" \
  -d "{\"config\":{\"ingress\":[{\"hostname\":\"$HOST\",\"service\":\"http://127.0.0.1:12000\"},{\"service\":\"http_status:404\"}]}}" >/dev/null

rec="$(api "$API/zones/$ZONE/dns_records?name=$HOST" \
  | python3 -c "import sys,json;r=json.load(sys.stdin).get('result') or [];print(r[0]['id'] if r else '')")"
if [ -z "$rec" ]; then
  api -X POST "$API/zones/$ZONE/dns_records" \
    -d "{\"type\":\"CNAME\",\"name\":\"${HOST%%.*}\",\"content\":\"$tid.cfargotunnel.com\",\"proxied\":true,\"ttl\":1}" >/dev/null
fi

api "$API/accounts/$ACC/cfd_tunnel/$tid/token" \
  | CRED="$CRED" python3 -c '
import base64, json, os, sys
tok = json.load(sys.stdin)["result"]
pad = tok + "=" * (-len(tok) % 4)
d = json.loads(base64.b64decode(pad))
json.dump({"AccountTag": d["a"], "TunnelID": d["t"], "TunnelSecret": d["s"]}, open(os.environ["CRED"], "w"))
os.chmod(os.environ["CRED"], 0o600)
print("wrote", os.environ["CRED"])
'

echo "tunnel id: $tid"
