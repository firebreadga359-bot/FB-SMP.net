#!/usr/bin/env bash
#
# FB-SMP one-shot VPS provisioning
# --------------------------------
# Run once on a fresh Debian 12+ / Ubuntu 22.04+ box to get the whole stack
# running 24/7 under systemd:
#
#     sudo bash deploy/provision-vps.sh
#
# It clones the repository to a stable path and runs the installer. Re-running
# it pulls the latest code and re-applies the install; the world and secrets
# are preserved. This is the same script the GitHub auto-deploy workflow calls.
#
# Environment overrides:
#     FBSMP_BRANCH=main                    branch to deploy
#     FBSMP_REPO_URL=<git url>             clone URL
#     FBSMP_REPO_DIR=/opt/fb-smp-repo      where to keep the checkout
#
set -euo pipefail

[[ "$(id -u)" -eq 0 ]] || { echo "run as root (sudo bash deploy/provision-vps.sh)" >&2; exit 1; }

REPO_URL="${FBSMP_REPO_URL:-https://github.com/firebreadga359-bot/FB-SMP.net.git}"
REPO_DIR="${FBSMP_REPO_DIR:-/opt/fb-smp-repo}"
BRANCH="${FBSMP_BRANCH:-main}"

say() { printf '\n==> %s\n' "$*"; }

say "Installing prerequisites (git, ca-certificates, curl)"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y git ca-certificates curl

if [[ -d "$REPO_DIR/.git" ]]; then
  say "Updating existing checkout at $REPO_DIR"
  git -C "$REPO_DIR" fetch --all --prune
  git -C "$REPO_DIR" checkout -f "$BRANCH" 2>/dev/null || git -C "$REPO_DIR" checkout -f -B "$BRANCH" "origin/$BRANCH"
  git -C "$REPO_DIR" reset --hard "origin/$BRANCH"
else
  say "Cloning $REPO_URL ($BRANCH) to $REPO_DIR"
  mkdir -p "$(dirname "$REPO_DIR")"
  git clone --branch "$BRANCH" "$REPO_URL" "$REPO_DIR"
fi

say "Running the installer (branch: $BRANCH)"
FBSMP_BRANCH="$BRANCH" bash "$REPO_DIR/deploy/deploy.sh"

cat <<EOF

==> Provisioning complete.

The stack is installed under /opt/fb-smp and managed by systemd:
  systemctl status fb-smp-paper fb-smp-velocity fb-smp-playit \\
                   fb-smp-panel-bridge fb-smp-health

Next steps:
  1. Confirm the playit.gg tunnels and addresses:
       journalctl -u fb-smp-playit -f
  2. Arm Discord outage alerts:
       printf '%s' '<webhook-url>' > /opt/fb-smp/.fbsmp-alert-webhook
       chmod 600 /opt/fb-smp/.fbsmp-alert-webhook
       systemctl restart fb-smp-health
  3. To redeploy automatically on every push to $BRANCH, add these GitHub
     repository secrets (Settings -> Secrets and variables -> Actions):
       VPS_HOST     hostname or IP of this box
       VPS_USER     a user with passwordless sudo (or root)
       VPS_SSH_KEY  private key whose public half is in that user's
                    ~/.ssh/authorized_keys
       VPS_PORT     optional, defaults to 22
       VPS_REPO_DIR optional, defaults to $REPO_DIR
     See deploy/README.md ("Automatic deploys from GitHub").

To re-run this provisioning at any time:
  sudo bash $REPO_DIR/deploy/provision-vps.sh
EOF
