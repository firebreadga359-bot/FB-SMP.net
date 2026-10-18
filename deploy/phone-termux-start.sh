#!/data/data/com.termux/files/usr/bin/bash
# FB-SMP phone connector (Termux)
# ---------------------------------
# The Daytona sandbox cannot reach playit.gg (its API is firewalled). Your
# phone has normal internet, so it acts as the bridge:
#
#   players -> xxx.playit.gg -> this phone -> WSS tunnel -> Daytona sandbox
#
# What this script does on the phone:
#   1. starts the local relays (TCP 25565 + UDP 19132 -> sandbox WSS)
#   2. starts the playit.gg agent, which exposes those local ports publicly
#
# Usage (inside Termux):
#   bash phone-termux-start.sh
#
# First run: the script prints a playit claim URL. Open it in a browser,
# log in to playit.gg, and authorize. Then in the playit dashboard add:
#   - Minecraft Java    -> 127.0.0.1:25565
#   - Minecraft Bedrock -> 127.0.0.1:19132
# Playit then shows your public addresses (xxx.playit.gg:port) to give players.
set -e
cd "$(dirname "$0")"
SOCK=/tmp/playit.sock

WS_TCP_URL="${WS_TCP_URL:-wss://3000-826b241b-32c1-4cf5-a93f-38287f184ad6.daytonaproxy01.eu/}"
WS_UDP_URL="${WS_UDP_URL:-wss://3001-826b241b-32c1-4cf5-a93f-38287f184ad6.daytonaproxy01.eu/}"
export WS_TCP_URL WS_UDP_URL

echo "==> Installing dependencies (Termux)"
pkg install -y python >/dev/null 2>&1 || true
pip install --quiet --disable-pip-version-check websockets

if [ ! -x ./playit ]; then
  echo "==> Downloading playit agent"
  curl -fsSL --retry 3 -o playit \
    "https://github.com/playit-cloud/playit-agent/releases/download/v1.0.10/playit-linux-aarch64"
  chmod +x playit
fi
if [ ! -x ./playit-cli ]; then
  curl -fsSL --retry 3 -o playit-cli \
    "https://github.com/playit-cloud/playit-agent/releases/download/v1.0.10/playit-cli-linux-aarch64"
  chmod +x playit-cli
fi

echo "==> Starting local relays (25565 TCP, 19132 UDP -> sandbox)"
python fb-smp-connect.py > relay.log 2>&1 &
RELAY_PID=$!
sleep 3
if ! kill -0 "$RELAY_PID" 2>/dev/null; then
  echo "Relay failed to start:"; cat relay.log; exit 1
fi

echo "==> Starting playit daemon"
setsid ./playit --socket-path "$SOCK" > playitd.log 2>&1 < /dev/null &
sleep 5

if grep -q secret_key "$HOME/.config/playit_gg/playit.toml" 2>/dev/null; then
  echo "==> Agent already claimed. Running."
else
  echo "==> Not claimed yet. Open the URL below, then add the two tunnels."
  ./playit-cli --socket-path "$SOCK" setup || true
fi

echo
echo "Keep this session open. Relay log: relay.log | Agent log: playitd.log"
wait
