#!/usr/bin/env bash
# Answer one question, from anywhere: can I shoot right now, and if not, which link is broken?
#
# Why this exists: away from home the chain has five independent links, and every one of them
# fails with the same symptom on the lens — nothing happens. Guessing which one costs the shoot.
# This walks them in dependency order and stops at the first break with the fix attached:
#
#   1. this Mac has internet
#   2. token-service (Vercel, public) answers and the local token is accepted
#   3. it advertises a luma address, and that address is ALIVE — a dead tunnel keeps being
#      served with HTTP 200 forever (see RemoteConfigResolver's kdoc), so it must be probed,
#      not trusted. This is the mac mini link: if it broke, the machine slept or the tunnel died.
#   4. the glasses are reachable over adb — this is the ONLY link that needs the Mac and the
#      glasses on the same network. Voice/coach do not: the glasses talk to the internet
#      directly. So a failure here costs the RECORDING, not the conversation.
#   5. the app is installed and its screen is in a usable state
#
# Only step 4 depends on the hotspot. If the phone's AP isolates its clients, the Mac cannot
# see the glasses at all and screen recording is impossible while the conversation still works
# perfectly — a confusing failure worth naming rather than debugging in the field.
#
#   ops/preflight.sh            # check everything, print a verdict
#   ops/preflight.sh --find     # also hunt for the glasses' new DHCP address and connect
#
# The address changes on every network, so --find scans for an open adb port: the iPhone
# hotspot's /28 has only 14 hosts, which is why this is fast enough to be worth doing.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="com.woolab.lumella"
FIND=""
[ "${1:-}" = "--find" ] && FIND=1
[ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ] && { sed -n '2,26p' "${BASH_SOURCE[0]}"; exit 0; }

FAIL=0
ok()   { printf '  \033[32mOK\033[0m    %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAIL=1; }
warn() { printf '  \033[33mWARN\033[0m  %s\n' "$1"; }

TOKEN="$(awk -F= '/^lumella\.localToken=/{sub(/^[^=]*=/,""); print; exit}' "$REPO_ROOT/local.properties" 2>/dev/null)"
TOKEN_SVC="$(awk -F= '/^lumella\.tokenServiceBaseUrl=/{sub(/^[^=]*=/,""); print; exit}' "$REPO_ROOT/local.properties" 2>/dev/null)"
TOKEN_SVC="${TOKEN_SVC:-https://lumella-token.vercel.app}"

echo "1. this Mac's internet"
if curl -s -m 8 -o /dev/null "https://api.openai.com/v1/models"; then
  ok "reachable (the glasses use their own path, but a dead link here fails everything below)"
else
  bad "no internet on this Mac — join the hotspot first"
fi

echo "2. token-service + local token"
if [ -z "$TOKEN" ]; then
  bad "lumella.localToken is empty in local.properties — the app shows TOKEN-FAIL"
else
  CODE="$(curl -s -m 12 -o /tmp/preflight-config.json -w '%{http_code}' \
      -H "X-Lumella-Local-Token: $TOKEN" "${TOKEN_SVC%/}/v1/config")"
  case "$CODE" in
    200) ok "token accepted" ;;
    401) bad "401 — this token != Vercel's LUMELLA_LOCAL_TOKEN. Voice will not start." ;;
    503) bad "503 — the service side is unset (no OPENAI_API_KEY on Vercel)" ;;
    *)   bad "HTTP $CODE from ${TOKEN_SVC%/}/v1/config" ;;
  esac
fi

echo "3. luma (the mac mini, through the tunnel)"
LUMA="$(python3 -c 'import json;print(json.load(open("/tmp/preflight-config.json")).get("lumaBaseUrl",""))' 2>/dev/null)"
if [ -z "$LUMA" ]; then
  bad "no lumaBaseUrl advertised — cannot reach the coach; voice-only (DEGRADED)"
else
  echo "        $LUMA"
  # Probe, do not trust: a stale URL is served with 200 long after the tunnel dies.
  CAPS="$(curl -s -m 15 "$LUMA/v1/capabilities")"
  if grep -q '"coach":true' <<<"$CAPS"; then
    ok "alive, coach:true"
  elif [ -n "$CAPS" ]; then
    warn "answers but coach is not true — voice will work, coaching will not"
  else
    bad "no answer. The mac mini slept or the tunnel died — and pmset sleep 0 is the guard."
  fi
fi

echo "4. glasses over adb (needed for RECORDING only)"
DEV="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
if [ -z "$DEV" ] && [ -n "$FIND" ]; then
  MY="$(ipconfig getifaddr en0 2>/dev/null)"
  if [ -n "$MY" ]; then
    SUB="${MY%.*}"
    echo "        scanning $SUB.0/24 for an open adb port..."
    for i in $(seq 1 254); do
      if nc -z -G 1 "$SUB.$i" 5555 2>/dev/null; then
        adb connect "$SUB.$i:5555" >/dev/null 2>&1
        sleep 1
        DEV="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
        [ -n "$DEV" ] && echo "        found $SUB.$i" && break
      fi
    done
  fi
fi
if [ -z "$DEV" ]; then
  bad "no glasses. Conversation still works; you just cannot record."
  echo "        - re-run with --find to hunt the new DHCP address"
  echo "        - if --find finds nothing, the hotspot is isolating its clients:"
  echo "          turn OFF 'Maximize Compatibility' on iPhone, or use a cable for this take"
else
  ok "$DEV"
  echo "5. app on the glasses"
  VER="$(adb -s "$DEV" shell dumpsys package "$PKG" 2>/dev/null | awk -F= '/versionName/{print $2; exit}' | tr -d '\r')"
  if [ -z "$VER" ]; then
    bad "$PKG is not installed — run ops/install-glasses.sh"
  else
    ok "installed (versionName=$VER)"
    STATUS="$(ANDROID_SERIAL="$DEV" "$REPO_ROOT/ops/screen-dump.sh" 2>/dev/null | awk '/tvStatus/{print $NF; exit}' | tr -d '"')"
    case "$STATUS" in
      Ready|Listening...) ok "screen says $STATUS" ;;
      "")                 warn "no text on screen — is the app in the foreground? screen awake?" ;;
      *)                  warn "screen says $STATUS (TOKEN-FAIL/DEGRADED/ACCOUNT_BLOCKED are all real states)" ;;
    esac
  fi
fi

echo
if [ "$FAIL" -eq 0 ]; then
  echo "READY — record with:"
  echo "  adb -s ${DEV:-<device>} shell screenrecord --time-limit 180 --size 1280x480 /sdcard/take1.mp4"
  echo "  adb -s ${DEV:-<device>} pull /sdcard/take1.mp4 ~/shots/"
else
  echo "NOT READY — fix the FAIL above."
fi
exit "$FAIL"
