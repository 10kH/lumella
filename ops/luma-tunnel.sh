#!/usr/bin/env bash
# Starts a cloudflared quick tunnel for the local luma-api (Mac mini, 127.0.0.1:8010) and
# publishes the resulting public URL to the Vercel project `lumella-token`'s LUMA_BASE_URL
# (production), then redeploys — so the glasses app can pick up the new URL via
# `GET /v1/config` at boot, WITHOUT an APK rebuild (see api/config.js).
#
# WHY: cloudflared quick tunnels mint a fresh https://<random-words>.trycloudflare.com URL on
# every restart. Before this script, that URL had to be hand-copied into local.properties and
# the APK rebuilt + reinstalled every time it changed. lumella-token.vercel.app is a stable
# address, so republishing LUMA_BASE_URL there breaks that coupling.
#
# Usage:
#   ops/luma-tunnel.sh
#
# Requires: cloudflared, and the vercel CLI already logged in with this directory linked to the
# `lumella-token` project (see .vercel/project.json — already checked in, gitignore-safe: it
# holds no secrets, just project/org ids).
#
# Runs in the foreground: leave it running while devices need luma-api reachability outside the
# home LAN. Ctrl-C (or any exit) tears down the tunnel; the last-published LUMA_BASE_URL is left
# in place on Vercel until the next run.
set -euo pipefail

LOCAL_URL="http://127.0.0.1:8010"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$REPO_ROOT/tmp/luma-tunnel"
CF_LOG="$LOG_DIR/cloudflared.log"

command -v cloudflared >/dev/null 2>&1 || { echo "ERROR: cloudflared not found on PATH" >&2; exit 1; }
command -v vercel >/dev/null 2>&1 || { echo "ERROR: vercel CLI not found on PATH" >&2; exit 1; }

mkdir -p "$LOG_DIR"
: > "$CF_LOG"

cloudflared tunnel --url "$LOCAL_URL" >>"$CF_LOG" 2>&1 &
CF_PID=$!

cleanup() {
  kill "$CF_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "waiting for cloudflared quick tunnel URL (pid=$CF_PID, log=$CF_LOG)..."
TUNNEL_URL=""
for _ in $(seq 1 30); do
  TUNNEL_URL="$(grep -oE 'https://[a-zA-Z0-9-]+\.trycloudflare\.com' "$CF_LOG" | head -n1 || true)"
  [ -n "$TUNNEL_URL" ] && break
  kill -0 "$CF_PID" 2>/dev/null || { echo "ERROR: cloudflared exited before publishing a URL; see $CF_LOG" >&2; exit 1; }
  sleep 1
done
[ -n "$TUNNEL_URL" ] || { echo "ERROR: timed out waiting for a tunnel URL; see $CF_LOG" >&2; exit 1; }
echo "tunnel URL: $TUNNEL_URL"

echo "publishing LUMA_BASE_URL=$TUNNEL_URL to Vercel project lumella-token (production)..."
( cd "$REPO_ROOT" && vercel env rm LUMA_BASE_URL production --yes >/dev/null 2>&1 || true )
( cd "$REPO_ROOT" && printf '%s' "$TUNNEL_URL" | vercel env add LUMA_BASE_URL production )
( cd "$REPO_ROOT" && vercel deploy --prod --yes )

echo ""
echo "==================================================================="
echo "luma-api reachable outside the LAN via: $TUNNEL_URL"
echo "glasses app resolves this automatically via GET /v1/config at boot."
echo "==================================================================="
echo ""
echo "tunnel running in foreground (pid=$CF_PID) — Ctrl-C to stop."

wait "$CF_PID"
