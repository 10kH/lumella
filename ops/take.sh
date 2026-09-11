#!/usr/bin/env bash
# One take: record the screen, optionally the wearer's view, collect both, and write down the
# subtitle text while it is still on screen.
#
# Why this exists: a take is never just the screen. The film needs the screen (subtitles and the
# voice/coach indicator) AND the first-person view, and they only line up if they are the same
# take. Running two commands by hand means one of them eventually gets started late or forgotten,
# and a take that cannot be used is not discovered until the edit.
#
# It also dumps the on-screen text per take, because transcribing Korean subtitles by eye from
# video is how typos get into the final cut (docs/MACBOOK-SETUP.md, layer B-aux).
#
#   ops/take.sh c7               # 180s, screen only
#   ops/take.sh c7 60            # 60s
#   ops/take.sh c7 60 --pov      # ... and the wearer's view at the same time
#
# The POV recording has no audio on purpose (rayneo-platform-notes.md §11): the device allows one
# audio input and the voice path owns it, so asking for audio would end the conversation being
# filmed. Sound comes from the external camera.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="com.woolab.lumella"
NAME="${1:?usage: ops/take.sh <name> [seconds] [--pov]}"
SEC="${2:-180}"
POV=""
[ "${3:-}" = "--pov" ] && POV=1
OUT="${SHOTS_DIR:-$HOME/shots}"
mkdir -p "$OUT"

DEV="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
if [ -z "$DEV" ]; then
  echo "no device. ops/preflight.sh --find" >&2
  exit 1
fi

STAMP="$(date +%H%M%S)"
echo "[$NAME] ${SEC}s — $(date '+%H:%M:%S')${POV:+ (+POV)}"

# Start the POV recording FIRST: it is a broadcast that returns immediately, whereas screenrecord
# blocks. Starting it second would leave the wearer's view short at the head of every take.
if [ -n "$POV" ]; then
  adb -s "$DEV" shell am broadcast -p "$PKG" -a "$PKG.DEBUG_REC_START" --es name "$NAME" >/dev/null 2>&1
fi

adb -s "$DEV" shell "screenrecord --time-limit $SEC --size 1280x480 /sdcard/$NAME.mp4"

if [ -n "$POV" ]; then
  adb -s "$DEV" shell am broadcast -p "$PKG" -a "$PKG.DEBUG_REC_STOP" >/dev/null 2>&1
  # Finalize is asynchronous; pulling immediately gets a truncated file.
  sleep 3
fi

adb -s "$DEV" pull "/sdcard/$NAME.mp4" "$OUT/$NAME-$STAMP-screen.mp4" >/dev/null 2>&1
adb -s "$DEV" shell "rm -f /sdcard/$NAME.mp4"
echo "  screen $OUT/$NAME-$STAMP-screen.mp4"

if [ -n "$POV" ]; then
  REMOTE="/storage/emulated/0/Android/data/$PKG/files/$NAME.mp4"
  if adb -s "$DEV" pull "$REMOTE" "$OUT/$NAME-$STAMP-pov.mp4" >/dev/null 2>&1; then
    # Delete on the device: POV runs ~189 MB/min and /sdcard holds about 114 minutes of it.
    adb -s "$DEV" shell "rm -f $REMOTE"
    echo "  pov    $OUT/$NAME-$STAMP-pov.mp4"
  else
    echo "  pov    MISSING — check: adb logcat -d | grep 'rec '" >&2
  fi
fi

ANDROID_SERIAL="$DEV" "$REPO_ROOT/ops/screen-dump.sh" > "$OUT/$NAME-$STAMP.txt" 2>/dev/null
echo "  text   $OUT/$NAME-$STAMP.txt"

# Frame count is the honest check: a static screen yields 1 frame and that is normal, but a take
# meant to show a conversation with 1 frame means nothing changed and the take is empty.
for f in "$OUT/$NAME-$STAMP-screen.mp4" "$OUT/$NAME-$STAMP-pov.mp4"; do
  [ -f "$f" ] || continue
  ffprobe -v error -show_entries stream=nb_frames -show_entries format=duration,size \
    -of default=noprint_wrappers=1 "$f" 2>/dev/null |
    awk -v n="$(basename "$f")" -F= '
      /nb_frames/ && !seen {fr=$2; seen=1} /duration/{d=$2} /size/{s=$2}
      END {printf "  %-34s %s frames / %.1fs / %.1fMB\n", n, fr, d, s/1048576}'
done
