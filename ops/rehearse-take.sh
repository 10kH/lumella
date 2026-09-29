#!/usr/bin/env bash
# A rehearsal take without a wearer: records a take (ops/take.sh --pov) while the script's lines
# are fed in through the debug hooks, one every GAP seconds, exactly as ops/scenario-check.sh does
# (DEBUG_SAY opens the turn and the tutor answers; DEBUG_EVENT input_transcript gives the coach and
# the record the text). Produces the same files as a real take, including <take>-app.log, so the
# film can be assembled from it before the shoot (aaai27 video-aifesta/assemble.py).
#
#   ops/rehearse-take.sh <take-name> <lines-file> <first> <last> [gap-seconds]
#   ops/rehearse-take.sh rh-a script-v3.txt 1 6        # lines 1-6
#
# Lines starting with '#' and blank lines in the file are skipped; numbering counts script lines.
set -uo pipefail
cd "$(dirname "$0")/.."
NAME="${1:?take name}"; FILE="${2:?lines file}"; FIRST="${3:?first line}"; LAST="${4:?last line}"; GAP="${5:-20}"
PKG=com.woolab.lumella
DEV="${ANDROID_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"
[ -n "$DEV" ] || { echo "no device" >&2; exit 1; }
export ANDROID_SERIAL="$DEV"
LINES=()
while IFS= read -r l; do
  case "$l" in ''|'#'*) continue ;; esac
  LINES+=("$l")
done < "$FILE"
if [ "$LAST" -gt "${#LINES[@]}" ] || [ "$FIRST" -lt 1 ]; then
  echo "lines $FIRST-$LAST: the file has ${#LINES[@]}" >&2; exit 2
fi
./ops/take.sh "$NAME" --start --pov || exit 1
# Stop the take however this ends — an interrupted run must not leave the device recording.
trap './ops/take.sh "$NAME" --stop' EXIT
sleep 3
for i in $(seq "$FIRST" "$LAST"); do
  line="${LINES[$((i - 1))]}"
  echo "  $i: $line"
  adb -s "$DEV" shell "am broadcast -p $PKG -a $PKG.DEBUG_SAY --es text '$line'" </dev/null >/dev/null
  sleep 1
  adb -s "$DEV" shell "am broadcast -p $PKG -a $PKG.DEBUG_EVENT --es json 'input_transcript:$line'" </dev/null >/dev/null
  sleep "$GAP"
done
