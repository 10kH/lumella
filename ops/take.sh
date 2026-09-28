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
# --stop leaves <take>-FINAL.mp4: the wearer's view (upright portrait) beside the glasses display,
# 1820x960, audio carrying both voices; for --audio, the display with the voices. That is the file for the edit; the parts are kept beside it, including
# <take>-learner.wav and <take>-tutor.wav for an edit that wants the voices on separate tracks.
#
#   ops/take.sh c7                   # blocking, 180s, screen only
#   ops/take.sh c7 60 --pov          # blocking, 60s, + wearer's view
#   ops/take.sh c7 --start --pov     # start and return; the wearer talks for as long as needed
#   ops/take.sh c7 --stop            # stop, collect, report
#
# The booth shoot is `--start --pov`. It used to make the tutor stutter and was shot around with
# --audio; since 2026-09-28 it runs the six booth sentences with no playback underrun at all
# (artifacts/perf/README.md). --audio remains for when the tutor still stutters — then it is the
# network, and a take without the camera is the fallback, not the plan.
#
# --start/--stop exist because the blocking form cannot be driven from a shell that ends: the
# recording died with its parent and the POV kept running unattended to 358 MB before it was
# noticed (2026-09-14). With --start, screenrecord runs detached ON THE DEVICE, so nothing on
# this Mac can orphan it.
#
# The POV recording is video only. Recording the mic in it too made CameraX open the microphone a
# second time and AAC-encode it in software, on a device the camera already pushes to its limit
# (artifacts/perf/README.md). The app writes both voices instead, from PCM it already has, on
# the video's clock: the learner exactly as the tutor heard it, and the tutor's own voice.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="com.woolab.lumella"
REMOTE_SCREEN_DIR="/sdcard"
REMOTE_POV_DIR="/storage/emulated/0/Android/data/$PKG/files"
OUT="${SHOTS_DIR:-$HOME/shots}"

NAME="${1:?usage: ops/take.sh <name> [seconds|--start|--stop] [--pov|--audio]}"
# The app names POV segments <name>.mp4, <name>-2.mp4, <name>-3.mp4 when a photo turn splits
# the recording. A name that already ends in -<digits> is indistinguishable from a segment:
# "baseline-pov-2" continued as "baseline-pov-3.mp4" and this script, looking for
# "baseline-pov-2-2.mp4", never pulled it (2026-09-28). Refuse the ambiguous name up front.
if [[ "$NAME" =~ -[0-9]+$ ]]; then
  echo "take name '$NAME' ends in -<number>, which collides with POV segment numbering; use e.g. '${NAME%-*}-${NAME##*-}x' or '${NAME%-*}${NAME##*-}'" >&2
  exit 2
fi
shift
MODE="block"
SEC=180
POV=""
AUDIO=""
for a in "$@"; do
  case "$a" in
    --start) MODE="start" ;;
    --stop)  MODE="stop" ;;
    --pov)   POV=1 ;;
    # Fallback: both voices and the screen, without the camera. The voice taps and the POV are
    # started by the same broadcast but are otherwise independent, so a take that only needs
    # sound does not pay for the camera — still the heaviest thing this device does
    # (artifacts/perf/README.md).
    --audio) AUDIO=1 ;;
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
  local expected=0 f d i
  # By index, not by glob: a glob sorts -10 before -2.
  for i in $(seq 1 "$count"); do
    if [ "$i" = 1 ]; then f="$OUT/$NAME-$stamp-pov.mp4"; else f="$OUT/$NAME-$stamp-pov-$i.mp4"; fi
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

# Mix the voice taps into one track, and put it under the wearer's view. Whichever taps came back
# are used — one missing tap must not leave the take silent. Taps and the silent POV stay on disk:
# the mix is a convenience, and an edit may well want separate tracks.
mix_voices() {
  local stamp="$1"
  local voices="$OUT/$NAME-$stamp-voices.wav" who f
  local inputs=() n=0
  for who in learner tutor; do
    f="$OUT/$NAME-$stamp-$who.wav"
    [ -f "$f" ] && { inputs+=(-i "$f"); n=$((n + 1)); }
  done
  [ "$n" = 0 ] && return 0
  local mixed=1
  if [ "$n" = 2 ]; then
    ffmpeg -v error "${inputs[@]}" \
      -filter_complex "[0:a][1:a]amix=inputs=2:duration=longest:dropout_transition=0:normalize=0[a]" \
      -map "[a]" -y "$voices" 2>/dev/null || mixed=0
  else
    ffmpeg -v error "${inputs[@]}" -c copy -y "$voices" 2>/dev/null || mixed=0
  fi
  if [ "$mixed" = 1 ]; then
    echo "  voices $voices  ($n of 2 voices)"
  else
    echo "  voices mix FAILED — the taps are intact, mix by hand" >&2
    return 0
  fi
  local pov="$OUT/$NAME-$stamp-pov-JOINED.mp4"
  [ -f "$pov" ] || pov="$OUT/$NAME-$stamp-pov.mp4"
  [ -f "$pov" ] || return 0
  local out="$OUT/$NAME-$stamp-pov-MIXED.mp4"
  if ffmpeg -v error -i "$pov" -i "$voices" -map 0:v -map 1:a -c:v copy -c:a aac -b:a 128k \
       -y "$out" 2>/dev/null; then
    echo "  mixed  $out  (view + voices)"
  else
    echo "  mix    FAILED — view and voices are intact, mux by hand" >&2
  fi
}

# Put the wearer's view beside what the glasses were showing, so one file carries both halves of
# the evidence: the scene and the subtitles/indicator that prove which layer did what.
#
# The screen capture is 1280x480 because the display is binocular — two 640x480 eyes side by
# side showing the same thing. Only the left eye is kept; the right is a duplicate and including
# it would halve the legible text size for nothing.
#
# The POV is stored 1280x720 with 90° rotation metadata: upright it is PORTRAIT (720x1280). This
# used to be composed with -noautorotate and stacked above the screen, which laid the view on its
# side in every FINAL — the comment promised a rotation the filter never did. ffmpeg now applies
# the metadata, and the portrait view goes beside the screen at the same height.
#
# Without a POV (--audio) the FINAL is the screen with the voices under it.
compose() {
  local stamp="$1"
  local screen="$OUT/$NAME-$stamp-screen.mp4" voices="$OUT/$NAME-$stamp-voices.wav"
  local out="$OUT/$NAME-$stamp-FINAL.mp4"
  [ -f "$screen" ] || return 0
  local pov="$OUT/$NAME-$stamp-pov-MIXED.mp4"
  [ -f "$pov" ] || pov="$OUT/$NAME-$stamp-pov-JOINED.mp4"
  [ -f "$pov" ] || pov="$OUT/$NAME-$stamp-pov.mp4"
  local ok=0
  if [ -f "$pov" ]; then
    # -map 0:a? — a take whose voices failed to mix still gets its picture.
    ffmpeg -v error -i "$pov" -i "$screen" -filter_complex \
      "[1:v]crop=640:480:0:0,scale=1280:960,setsar=1[scr];[0:v]scale=-2:960,setsar=1[pv];[pv][scr]hstack=inputs=2[v]" \
      -map "[v]" -map "0:a?" -c:v libx264 -preset veryfast -crf 20 -c:a aac -y "$out" 2>/dev/null && ok=1
  elif [ -f "$voices" ]; then
    # screenrecord writes a frame only when the display changes, so its file ends at the last
    # change, not at the end of the take. Hold that last frame, and cut at the voices' length
    # with -t: -shortest overshoots by tens of seconds against an endless padded stream.
    local dur
    dur="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$voices" 2>/dev/null)"
    [ -n "$dur" ] && ffmpeg -v error -i "$screen" -i "$voices" \
      -filter_complex "[0:v]crop=640:480:0:0,scale=1280:960,setsar=1,tpad=stop_mode=clone:stop=-1[v]" \
      -map "[v]" -map 1:a -c:v libx264 -preset veryfast -crf 20 -c:a aac -t "$dur" -y "$out" 2>/dev/null && ok=1
  else
    return 0
  fi
  if [ "$ok" = 1 ]; then
    local wh
    wh="$(ffprobe -v error -select_streams v -show_entries stream=width,height -of csv=p=0 "$out" 2>/dev/null)"
    echo "  FINAL  $out  ($wh)"
  else
    echo "  FINAL  compose failed — the separate files are intact" >&2
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
    #
    # Order by the segment index, not by a field of the name: `sort -t- -k2` read "audio" out of
    # "perf-audio-path-2.mp4" and put the 122s second segment first, so the joined take played
    # its end before its beginning (2026-09-28).
    seg_count=0
    for remote in $(adb -s "$DEV" shell "ls $REMOTE_POV_DIR/ 2>/dev/null" | tr -d '\r' \
                    | grep -E "^$NAME(-[0-9]+)?\.mp4$" \
                    | awk -v n="$NAME" '{ i = ($0 == n ".mp4") ? 1 : substr($0, length(n) + 2, length($0) - length(n) - 5); print i "\t" $0 }' \
                    | sort -n | cut -f2); do
      seg_count=$((seg_count + 1))
      if [ "$seg_count" = "1" ]; then local_name="$OUT/$NAME-$stamp-pov.mp4"
      else local_name="$OUT/$NAME-$stamp-pov-$seg_count.mp4"; fi
      if adb -s "$DEV" pull "$REMOTE_POV_DIR/$remote" "$local_name" >/dev/null 2>&1; then
        # Delete on the device: a POV is ~43 MB/min (720p 24fps, measured 9/28) and takes add up.
        adb -s "$DEV" shell "rm -f $REMOTE_POV_DIR/$remote"
        echo "  pov    $local_name"
      else
        echo "  pov    MISSING $remote — adb logcat -d | grep 'rec '" >&2
      fi
    done
    [ "$seg_count" = "0" ] && echo "  pov    MISSING — adb logcat -d | grep 'rec '" >&2
    [ "$seg_count" -gt 1 ] && join_segments "$stamp" "$seg_count"
  fi

  # Both voices, tapped by the app on the video's clock. Reached on --audio too: the voices are
  # the whole point of that mode. A screen-only take has none, and says nothing about them.
  local who remote
  for who in learner tutor; do
    remote="$REMOTE_POV_DIR/$NAME-$who.wav"
    if adb -s "$DEV" pull "$remote" "$OUT/$NAME-$stamp-$who.wav" >/dev/null 2>&1; then
      adb -s "$DEV" shell "rm -f $remote"
      printf "  %-7s %s\n" "$who" "$OUT/$NAME-$stamp-$who.wav"
    elif [ -n "$POV" ] || [ -n "$AUDIO" ]; then
      echo "  $who MISSING — adb logcat -d | grep -E 'tap |take clock'" >&2
    fi
  done
  mix_voices "$stamp"

  compose "$stamp"

  ANDROID_SERIAL="$DEV" "$REPO_ROOT/ops/screen-dump.sh" > "$OUT/$NAME-$stamp.txt" 2>/dev/null
  echo "  text   $OUT/$NAME-$stamp.txt"

  # Frame count is the honest check: a static screen yields 1 frame and that is normal, but a take
  # meant to show a conversation with 1 frame means nothing changed and the take is empty.
  for f in "$OUT/$NAME-$stamp-screen.mp4" "$OUT/$NAME-$stamp-pov"*.mp4; do
    [ -f "$f" ] || continue
    case "$f" in *-JOINED.mp4|*-MIXED.mp4|*-FINAL.mp4) continue ;; esac
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
  # --audio: fire the same broadcast, but do not wait on the POV file — there will not be one.
  if [ -n "$AUDIO" ] && [ -z "$POV" ]; then
    adb -s "$DEV" shell am broadcast -p "$PKG" -a "$PKG.DEBUG_REC_START" --es name "$NAME" --ez camera false >/dev/null 2>&1
    return 0
  fi
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
    # the head offset. Screen is the cheap one (0.26 MB/min against ~43), and a few seconds of it
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
    # The mode goes with the stamp, so --stop knows what to collect without being told again.
    echo "$(date +%H%M%S) ${POV:+pov}${AUDIO:+audio}" > "/tmp/lumella-take-$NAME.stamp"
    if [ -n "$POV" ]; then   mode="screen + POV"
    elif [ -n "$AUDIO" ]; then mode="screen + both voices (no camera)"
    else                     mode="screen"
    fi
    echo "$NAME: recording ($mode). stop with: ops/take.sh $NAME --stop"
    ;;
  stop)
    read -r STAMP SAVED_MODE < "/tmp/lumella-take-$NAME.stamp" 2>/dev/null || true
    STAMP="${STAMP:-$(date +%H%M%S)}"
    case "${SAVED_MODE:-}" in *pov*) POV=1 ;; esac
    case "${SAVED_MODE:-}" in *audio*) AUDIO=1 ;; esac
    stop_pov
    # SIGINT so screenrecord finalises the container; SIGKILL leaves an unplayable file.
    adb -s "$DEV" shell "pkill -INT screenrecord" >/dev/null 2>&1
    sleep 3
    collect "$STAMP"
    rm -f "/tmp/lumella-take-$NAME.stamp"
    ;;
  block)
    STAMP="$(date +%H%M%S)"
    echo "[$NAME] ${SEC}s — $(date '+%H:%M:%S')${POV:+ (+POV)}${AUDIO:+ (+audio)}"
    start_pov
    adb -s "$DEV" shell "screenrecord --time-limit $SEC --size 1280x480 $REMOTE_SCREEN_DIR/$NAME.mp4"
    { [ -n "$POV" ] || [ -n "$AUDIO" ]; } && stop_pov
    collect "$STAMP"
    ;;
esac
