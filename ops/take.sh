#!/usr/bin/env bash
# One take: the screen, optionally the wearer's view, both collected together, plus the subtitle
# text while it is still on screen.
#
# Why this exists: a take is never just the screen. The film needs the screen (subtitles and the
# voice/coach indicator) AND the first-person view, and they only line up if they are the same
# take. Running two commands by hand means one eventually starts late or is forgotten, and a take
# that cannot be used is not discovered until the edit.
#
# It also dumps the on-screen text per take, because transcribing Korean subtitles by eye from
# video is how typos get into the final cut (docs/MACBOOK-SETUP.md, layer B-aux).
#
#   ops/take.sh c7                   # blocking, 180s, screen only
#   ops/take.sh c7 60 --pov          # blocking, 60s, + wearer's view
#   ops/take.sh c7 --start --pov     # start and return; the wearer talks for as long as needed
#   ops/take.sh c7 --stop            # stop, collect, report
#
# --start/--stop exist because the blocking form cannot be driven from a shell that ends: the
# recording died with its parent and the POV kept running unattended to 358 MB before it was
# noticed (2026-09-14). With --start, screenrecord runs detached ON THE DEVICE, so nothing on
# this Mac can orphan it.
#
# The POV recording has no audio on purpose (rayneo-platform-notes.md §11): the device allows one
# audio input and the voice path owns it, so asking for audio would end the conversation being
# filmed. Sound comes from the external camera.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="com.woolab.lumella"
REMOTE_SCREEN_DIR="/sdcard"
REMOTE_POV_DIR="/storage/emulated/0/Android/data/$PKG/files"
OUT="${SHOTS_DIR:-$HOME/shots}"

NAME="${1:?usage: ops/take.sh <name> [seconds|--start|--stop] [--pov]}"
shift
MODE="block"
SEC=180
POV=""
for a in "$@"; do
  case "$a" in
    --start) MODE="start" ;;
    --stop)  MODE="stop" ;;
    --pov)   POV=1 ;;
    ''|*[!0-9]*) echo "unknown argument: $a" >&2; exit 2 ;;
    *)       SEC="$a" ;;
  esac
done
mkdir -p "$OUT"

DEV="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
if [ -z "$DEV" ]; then
  echo "no device. ops/preflight.sh --find" >&2
  exit 1
fi

# Photo turns split a take into segments; nobody should have to reassemble them by hand at the
# edit. The parts all share one encoder config, so the concat demuxer joins them with -c copy:
# no re-encode, no quality loss, about a second. Segments are KEPT — the join is a convenience,
# and a take is not worth risking to a muxing surprise.
join_segments() {
  local stamp="$1" count="$2"
  local joined="$OUT/$NAME-$stamp-pov-JOINED.mp4"
  local list; list="$(mktemp -t lumella-join)"
  local expected=0 f d
  for f in "$OUT/$NAME-$stamp-pov.mp4" "$OUT/$NAME-$stamp-pov-"[0-9]*.mp4; do
    [ -f "$f" ] || continue
    printf "file '%s'\n" "$f" >>"$list"
    d="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$f" 2>/dev/null)"
    expected="$(awk -v a="$expected" -v b="${d:-0}" 'BEGIN{print a+b}')"
  done
  if ffmpeg -v error -f concat -safe 0 -i "$list" -c copy -y "$joined" 2>/dev/null; then
    local got
    got="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$joined" 2>/dev/null)"
    # A silent short join is the failure that would actually cost footage, so compare lengths
    # rather than trusting ffmpeg's exit code alone.
    if awk -v g="${got:-0}" -v e="$expected" 'BEGIN{exit !(g > e-1 && g < e+1)}'; then
      echo "  joined $joined  ($count segments, $(printf '%.1f' "$got")s)"
    else
      echo "  joined $joined  — WARNING: $(printf '%.1f' "${got:-0}")s vs $(printf '%.1f' "$expected")s expected; use the segments" >&2
    fi
  else
    echo "  join   FAILED — segments are intact, join by hand" >&2
  fi
  rm -f "$list"
}

# Mix the learner (video track) and the tutor (tapped WAV) into one file. Both halves stay on
# disk: the mix is a convenience, and an edit may well want them on separate tracks.
mix_voices() {
  local stamp="$1"
  local pov="$OUT/$NAME-$stamp-pov-JOINED.mp4"
  [ -f "$pov" ] || pov="$OUT/$NAME-$stamp-pov.mp4"
  [ -f "$pov" ] || return 0
  local wav="$OUT/$NAME-$stamp-tutor.wav"
  local out="$OUT/$NAME-$stamp-pov-MIXED.mp4"
  if ffmpeg -v error -i "$pov" -i "$wav" \
       -filter_complex "[0:a][1:a]amix=inputs=2:duration=longest:dropout_transition=0[a]" \
       -map 0:v -map "[a]" -c:v copy -c:a aac -y "$out" 2>/dev/null; then
    echo "  mixed  $out  (learner + tutor)"
  else
    echo "  mix    FAILED — both halves are intact, mix by hand" >&2
  fi
}

collect() {
  local stamp="$1"
  adb -s "$DEV" pull "$REMOTE_SCREEN_DIR/$NAME.mp4" "$OUT/$NAME-$stamp-screen.mp4" >/dev/null 2>&1
  adb -s "$DEV" shell "rm -f $REMOTE_SCREEN_DIR/$NAME.mp4"
  echo "  screen $OUT/$NAME-$stamp-screen.mp4"

  if [ -n "$POV" ] || adb -s "$DEV" shell "ls $REMOTE_POV_DIR/$NAME.mp4" >/dev/null 2>&1; then
    # A photo turn during a take closes the current segment and opens the next, so one take can
    # be $NAME.mp4, $NAME-2.mp4, $NAME-3.mp4 ... Pulling only the first name would silently
    # leave the rest of the take on the device.
    seg_count=0
    for remote in $(adb -s "$DEV" shell "ls $REMOTE_POV_DIR/ 2>/dev/null" | tr -d '\r' \
                    | grep -E "^$NAME(-[0-9]+)?\.mp4$" | sort -t- -k2 -n); do
      seg_count=$((seg_count + 1))
      if [ "$seg_count" = "1" ]; then local_name="$OUT/$NAME-$stamp-pov.mp4"
      else local_name="$OUT/$NAME-$stamp-pov-$seg_count.mp4"; fi
      if adb -s "$DEV" pull "$REMOTE_POV_DIR/$remote" "$local_name" >/dev/null 2>&1; then
        # Delete on the device: POV runs ~189 MB/min and /sdcard holds about 114 minutes of it.
        adb -s "$DEV" shell "rm -f $REMOTE_POV_DIR/$remote"
        echo "  pov    $local_name"
      else
        echo "  pov    MISSING $remote — adb logcat -d | grep 'rec '" >&2
      fi
    done
    [ "$seg_count" = "0" ] && echo "  pov    MISSING — adb logcat -d | grep 'rec '" >&2
    [ "$seg_count" -gt 1 ] && join_segments "$stamp" "$seg_count"
  fi

  # The tutor's voice, captured separately. The video's audio track is the microphone, and the
  # mic runs with the platform echo canceller so the tutor is deliberately absent from it — a
  # take otherwise carries only the learner's half of the conversation.
  local tutor_remote="$REMOTE_POV_DIR/$NAME-tutor.wav"
  if adb -s "$DEV" pull "$tutor_remote" "$OUT/$NAME-$stamp-tutor.wav" >/dev/null 2>&1; then
    adb -s "$DEV" shell "rm -f $tutor_remote"
    echo "  tutor  $OUT/$NAME-$stamp-tutor.wav"
    mix_voices "$stamp"
  fi

  ANDROID_SERIAL="$DEV" "$REPO_ROOT/ops/screen-dump.sh" > "$OUT/$NAME-$stamp.txt" 2>/dev/null
  echo "  text   $OUT/$NAME-$stamp.txt"

  # Frame count is the honest check: a static screen yields 1 frame and that is normal, but a take
  # meant to show a conversation with 1 frame means nothing changed and the take is empty.
  for f in "$OUT/$NAME-$stamp-screen.mp4" "$OUT/$NAME-$stamp-pov"*.mp4; do
    [ -f "$f" ] || continue
    case "$f" in *-JOINED.mp4|*-MIXED.mp4) continue ;; esac
    ffprobe -v error -show_entries stream=nb_frames -show_entries format=duration,size \
      -of default=noprint_wrappers=1 "$f" 2>/dev/null |
      awk -v n="$(basename "$f")" -F= '
        /nb_frames/ && !seen {fr=$2; seen=1} /duration/{d=$2} /size/{s=$2}
        END {printf "  %-34s %s frames / %.1fs / %.1fMB\n", n, fr, d, s/1048576}'
  done
}

# Fire-and-forget was not enough. On 2026-09-14 the broadcast was accepted, the file appeared,
# and then stopped growing at 10.9 MB — the screen kept recording and the POV did not, and the
# take came back 13.3s short at the HEAD, missing the camera-off opening the shot existed to
# prove. So: confirm the file is actually growing, and restart once if it is not.
start_pov() {
  [ -z "$POV" ] && return
  local remote="$REMOTE_POV_DIR/$NAME.mp4" a b attempt
  for attempt in 1 2; do
    adb -s "$DEV" shell am broadcast -p "$PKG" -a "$PKG.DEBUG_REC_START" --es name "$NAME" >/dev/null 2>&1
    sleep 3
    a=$(adb -s "$DEV" shell "ls -la $remote" 2>/dev/null | awk '{print $5}')
    sleep 3
    b=$(adb -s "$DEV" shell "ls -la $remote" 2>/dev/null | awk '{print $5}')
    if [ -n "$a" ] && [ -n "$b" ] && [ "$a" != "$b" ]; then
      return 0
    fi
    echo "  POV not advancing (${a:-none} -> ${b:-none}); restarting [$attempt/2]" >&2
    adb -s "$DEV" shell am broadcast -p "$PKG" -a "$PKG.DEBUG_REC_STOP" >/dev/null 2>&1
    sleep 3
  done
  echo "  WARNING: POV never advanced — shoot will be screen-only" >&2
}

stop_pov() {
  adb -s "$DEV" shell am broadcast -p "$PKG" -a "$PKG.DEBUG_REC_STOP" >/dev/null 2>&1
  # Finalize is asynchronous; pulling immediately gets a truncated file.
  sleep 3
}

case "$MODE" in
  start)
    # Screen first, POV second. The POV start now spends ~6s confirming the file is growing, and
    # whichever is started first runs during that wait — so the order decides which layer carries
    # the head offset. Screen is the cheap one (0.26 MB/min against 189), and a few seconds of it
    # before the wearer speaks costs nothing, while the same seconds missing from the POV cost the
    # opening of the shot. Neither order makes them equal; this one makes the surplus harmless.
    adb -s "$DEV" shell "screenrecord --time-limit 180 --size 1280x480 $REMOTE_SCREEN_DIR/$NAME.mp4 >/dev/null 2>&1 &" >/dev/null 2>&1
    start_pov
    # Detach ON THE DEVICE with a plain `&`. `setsid` was tried first and the process was gone
    # within seconds every time, while the bare background job keeps running (measured
    # 2026-09-14). Do not "improve" this back to setsid.
    #
    # 180 is screenrecord's own ceiling: --time-limit 900 is REJECTED and the process exits at
    # once, silently, leaving a take with no screen track. That is exactly how the first version
    # of this failed. A longer take needs --stop before the cap, or a second take.
    sleep 1
    # Check the process list, not the exit status: adb shell does not forward it.
    if [ "$(adb -s "$DEV" shell "pgrep screenrecord" 2>/dev/null | tr -d '\r' | grep -c .)" = "0" ]; then
      echo "WARNING: screen recording did not start; POV may still be running" >&2
    fi
    date +%H%M%S > "/tmp/lumella-take-$NAME.stamp"
    [ -n "$POV" ] && echo "$NAME: recording (screen + POV). stop with: ops/take.sh $NAME --stop" \
                  || echo "$NAME: recording (screen). stop with: ops/take.sh $NAME --stop"
    ;;
  stop)
    STAMP="$(cat "/tmp/lumella-take-$NAME.stamp" 2>/dev/null || date +%H%M%S)"
    stop_pov
    # SIGINT so screenrecord finalises the container; SIGKILL leaves an unplayable file.
    adb -s "$DEV" shell "pkill -INT screenrecord" >/dev/null 2>&1
    sleep 3
    collect "$STAMP"
    rm -f "/tmp/lumella-take-$NAME.stamp"
    ;;
  block)
    STAMP="$(date +%H%M%S)"
    echo "[$NAME] ${SEC}s — $(date '+%H:%M:%S')${POV:+ (+POV)}"
    start_pov
    adb -s "$DEV" shell "screenrecord --time-limit $SEC --size 1280x480 $REMOTE_SCREEN_DIR/$NAME.mp4"
    [ -n "$POV" ] && stop_pov
    collect "$STAMP"
    ;;
esac
