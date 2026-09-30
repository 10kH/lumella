#!/usr/bin/env bash
# Launch lumella on the glasses and wait for Ready — without losing to the launcher.
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
#   ops/launch-lumella.sh --topic "어제 친구와 한 일을 말해요"   # topic-guided conversation
#   ops/launch-lumella.sh --no-topic # back to an open conversation (the tutor asks what to talk about)
#   ops/launch-lumella.sh --no-topic --hold-opener   # ... but only when take.sh --opener says so
#
# The topic lives in topic.txt in the app's external files dir and is read at launch, so it
# survives relaunches until --no-topic; setting or clearing it restarts the app.

set -uo pipefail
S="${ANDROID_SERIAL:-A06B4A043084773}"
PKG="com.woolab.lumella"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESET=0; TOPIC_SET=0; TOPIC=""; HOLD=0
while [ $# -gt 0 ]; do
  case "$1" in
    --reset) RESET=1 ;;
    --topic) TOPIC_SET=1; TOPIC="${2:?--topic needs a topic}"; shift ;;
    --no-topic) TOPIC_SET=1; TOPIC="" ;;
    --hold-opener) HOLD=1 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done
TOPIC_PATH="/storage/emulated/0/Android/data/$PKG/files/topic.txt"
HOLD_PATH="/storage/emulated/0/Android/data/$PKG/files/hold-opener"

# lumella and ELLA fight over the mic; the later one wins silently.
adb -s "$S" shell pidof com.woolab.ella >/dev/null 2>&1 && {
  adb -s "$S" shell am force-stop com.woolab.ella >/dev/null 2>&1
  echo "  ELLA stopped (mic contention)"
}

if [ "$TOPIC_SET" = 1 ]; then
  if [ -n "$TOPIC" ]; then
    # adb push, not shell echo: the topic is Korean with spaces and the device shell re-splits.
    tmp="$(mktemp -t lumella-topic)"; printf '%s\n' "$TOPIC" > "$tmp"
    adb -s "$S" push "$tmp" "$TOPIC_PATH" >/dev/null 2>&1
    rm -f "$tmp"
    got="$(adb -s "$S" shell "cat $TOPIC_PATH" 2>/dev/null | tr -d '\r')"
    if [ "$got" != "$TOPIC" ]; then
      echo "  topic did not land on the glasses (read back: '${got}'); not launching an open conversation by mistake" >&2
      exit 1
    fi
    echo "  topic: $TOPIC"
  else
    adb -s "$S" shell "rm -f $TOPIC_PATH" >/dev/null 2>&1; echo "  topic cleared (open conversation)"
  fi
fi

if [ "$HOLD" = 1 ]; then
  # Read and deleted by the app at launch: this launch only.
  adb -s "$S" shell "touch $HOLD_PATH" >/dev/null 2>&1 && echo "  opening question held for take.sh --opener"
fi

if [ "$RESET" = 1 ] || [ "$TOPIC_SET" = 1 ] || [ "$HOLD" = 1 ]; then
  adb -s "$S" shell am force-stop "$PKG" >/dev/null 2>&1
  if [ "$RESET" = 1 ]; then
    adb -s "$S" shell "run-as $PKG rm -f files/learner-state.json files/learner-state.json.tmp files/recent-topics.txt files/last-topic.txt" 2>/dev/null
    echo "  learner-state.json and remembered topics wiped"
  fi
  echo "  waiting out the launcher's sweep"
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
