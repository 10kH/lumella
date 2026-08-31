#!/usr/bin/env bash
# Keeps lumella-token's advertised lumaBaseUrl pointing at something that actually answers.
#
# WHY THIS EXISTS: the glasses resolve luma at boot from <token-service>/v1/config. That value is
# only rewritten by ops/luma-tunnel.sh, so whenever the tunnel is down the endpoint keeps serving
# its last URL forever — measured 2026-08-31, a 33-day-old quick-tunnel host still served with
# HTTP 200 while luma-api answered fine on the LAN the whole time. The app now probes before
# adopting a remote URL, so it survives that, but it then falls back to the address baked into the
# APK — and this Mac is on DHCP, so that baked address goes stale the moment the router hands out
# a different lease. This script closes the remaining hole from the other side.
#
# WHY NOT A STATIC LEASE: the router is not administrable here. WHY NOT mDNS: the upstream DNS
# hijacks .local — the glasses resolve Woody-M4M.local to a public address (218.38.137.27), which
# would silently point the app at a stranger's host. An address this script publishes is always
# one it just verified itself.
#
# Deliberately conservative: it republishes ONLY when the currently advertised URL fails its own
# health check. A live tunnel URL outranks a LAN address (it works off-LAN too), so a healthy
# tunnel is never clobbered.
set -euo pipefail

# route(8) and ipconfig(8) live in /sbin and /usr/sbin, which launchd does not put on PATH. When
# they were missing the interface lookup returned empty and this script exited 0 having done
# nothing - a no-op indistinguishable from "already healthy" in the log. Guarantee them here
# rather than depending on how the caller was invoked.
export PATH="$PATH:/sbin:/usr/sbin"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PORT="${LUMA_PORT:-8010}"
PROBE_PATH="/v1/capabilities"

VERCEL_ENV_FILE="${VERCEL_ENV_FILE:-$HOME/.config/lumella/tunnel.env}"
if [[ -z "${VERCEL_TOKEN:-}" && -r "$VERCEL_ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  set -a; source "$VERCEL_ENV_FILE"; set +a
fi
[[ -n "${VERCEL_TOKEN:-}" ]] || { echo "ERROR: no VERCEL_TOKEN ($VERCEL_ENV_FILE unreadable)" >&2; exit 1; }
command -v vercel >/dev/null 2>&1 || { echo "ERROR: vercel CLI not on PATH" >&2; exit 1; }

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*"; }

# The interface behind the default route, not a hardcoded en0: the answer changes between Wi-Fi
# and Ethernet and a wrong guess would publish an address nothing can reach.
iface="$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')"
[[ -n "$iface" ]] || { log "no default route; nothing to publish"; exit 0; }
lan_ip="$(ipconfig getifaddr "$iface" 2>/dev/null || true)"
[[ -n "$lan_ip" ]] || { log "interface $iface has no IPv4 address yet"; exit 0; }

# Never advertise an address this host cannot serve. If luma-api is down the old value is left
# alone: replacing it with another dead URL would only trade one stale pointer for another.
if ! curl -sf -m 5 -o /dev/null "http://${lan_ip}:${PORT}${PROBE_PATH}"; then
  log "luma-api not answering on ${lan_ip}:${PORT}; leaving published value untouched"
  exit 0
fi

local_token="$(awk -F= '/^lumella\.localToken=/{sub(/^[^=]*=/,""); print; exit}' "$REPO_ROOT/local.properties" 2>/dev/null || true)"
token_service="$(awk -F= '/^lumella\.tokenServiceBaseUrl=/{sub(/^[^=]*=/,""); print; exit}' "$REPO_ROOT/local.properties" 2>/dev/null || true)"
token_service="${token_service:-https://lumella-token.vercel.app}"

published=""
if [[ -n "$local_token" ]]; then
  published="$(curl -s -m 10 -H "X-Lumella-Local-Token: ${local_token}" "${token_service%/}/v1/config" 2>/dev/null \
    | sed -n 's/.*"lumaBaseUrl"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
fi

# The health check is what makes this safe to run on a timer: a working tunnel URL passes it and
# we exit, so the more capable address wins by default.
if [[ -n "$published" ]] && curl -sf -m 8 -o /dev/null "${published%/}${PROBE_PATH}"; then
  log "published address still healthy ($published); nothing to do"
  exit 0
fi

desired="http://${lan_ip}:${PORT}"
log "published address unusable (${published:-none}); republishing $desired"

cd "$REPO_ROOT"
vercel env rm LUMA_BASE_URL production --yes >/dev/null 2>&1 || true
printf '%s' "$desired" | vercel env add LUMA_BASE_URL production >/dev/null 2>&1

# Vercel serves env values captured at build time, so the variable change alone changes nothing
# the glasses can see. Skipping this deploy is exactly the silent no-op this script exists to
# prevent.
vercel --prod --yes >/dev/null 2>&1

verify=""
if [[ -n "$local_token" ]]; then
  sleep 5
  verify="$(curl -s -m 10 -H "X-Lumella-Local-Token: ${local_token}" "${token_service%/}/v1/config" 2>/dev/null \
    | sed -n 's/.*"lumaBaseUrl"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
fi

if [[ "$verify" == "$desired" ]]; then
  log "published and verified: $desired"
else
  log "WARNING: published $desired but /v1/config reports '${verify:-unreadable}'"
  exit 1
fi
