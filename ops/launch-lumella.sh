#!/usr/bin/env bash
# Launch ELLA on the glasses and wait for Ready — without losing to the launcher.
#
# The RayNeo launcher (com.ffalconxr.mercury.launcher, pid ~1809) force-stops an app that is
# started within a couple of seconds of being force-stopped or reinstalled. Measured 2026-09-18:
#
#   08:42:16.817  START com.woolab.lumella/.MainActivity          <- am start
#   08:42:16.840  Force stopping com.woolab.lumella from pid 1809  <- launcher, 23ms later
#   08:42:16.857  start not valid, killing pid=15224
#
# A wearer sees ELLA flash and vanish; the next start a few seconds later sticks. So this script
# never force-stops before starting. If a clean state is needed (learner-state.json reset), it
# stops, deletes, WAITS, then starts.
#
#   ops/launch-lumella.sh            # bring ELLA to the front (start if needed), wait for Ready
#   ops/launch-lumella.sh --reset    # also wipe learner-state.json (needs the app stopped first)

set -uo pipefail
S="${ANDROID_SERIAL:-A06B4A043084773}"
PKG="com.woolab.lumella"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESET=0; [ "${1:-}" = "--reset" ] && RESET=1

# lumella and ELLA fight over the mic; the later one wins silently.
adb -s "$S" shell pidof com.woolab.ella >/dev/null 2>&1 && {
  adb -s "$S" shell am force-stop com.woolab.ella >/dev/null 2>&1
  echo "  ELLA stopped (mic contention)"
}

if [ "$RESET" = 1 ]; then
  adb -s "$S" shell am force-stop "$PKG" >/dev/null 2>&1
  adb -s "$S" shell "run-as $PKG rm -f files/learner-state.json files/learner-state.json.tmp" 2>/dev/null
  echo "  learner-state.json wiped; waiting out the launcher's sweep"
  sleep 5
fi

adb -s "$S" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sleep 1
# am start (not monkey): monkey's launch intent has been eaten by a dimming screen more than once.
adb -s "$S" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1

for i in $(seq 1 8); do
  sleep 4
  P="$(adb -s "$S" shell pidof "$PKG" 2>/dev/null | tr -d '\r')"
  if [ -z "$P" ]; then
    # Killed by the launcher; back off and start again.
    echo "  launcher killed the start; retrying in 4s"
    sleep 4
    adb -s "$S" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
    continue
  fi
  st="$(ANDROID_SERIAL="$S" "$REPO_ROOT/ops/screen-dump.sh" 2>/dev/null | awk '/tvStatus/{print; exit}' | sed 's/.*"\(.*\)".*/\1/')"
  case "$st" in
    Ready) echo "  Ready (pid $P, $((i*4))s)"; exit 0 ;;
    "Idle - tap to wake") adb -s "$S" shell am broadcast -p "$PKG" -a "$PKG.DEBUG_TAP" >/dev/null 2>&1 ;;
    *) : ;;
  esac
done
echo "  did not reach Ready; last status: ${st:-none}" >&2
exit 1
