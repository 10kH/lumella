#!/usr/bin/env bash
# Stands an upside-down wearer's view upright in a take already recorded, and rebuilds its FINAL.
#
# Takes recorded between 2026-09-28 and 2026-09-30 (GlassesCamera targetRotation ROTATION_180)
# carry rotation=90 in their metadata, and every player — ffmpeg, QuickTime, Final Cut — turns
# the view counter-clockwise onto its head (Woody, lt8 on 9/30: keyboard at the top, ceiling at
# the bottom). The pixels are fine; only the flag is wrong. This rewrites the flag to -90
# (stream copy: nothing is re-encoded, nothing lost) on every POV file and rebuilds the FINAL with
# take.sh --recompose.
#
#   ops/fix-pov-rotation.sh ~/shots/lt8-200609
set -uo pipefail
PREFIX="${1:?usage: ops/fix-pov-rotation.sh ~/shots/<name>-<stamp>}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
base="$(basename "$PREFIX")"; NAME="${base%-*}"; STAMP="${base##*-}"
fixed=0
# Every POV file of the take: -pov, -pov-2… (photo-turn segments), -pov-JOINED, -pov-MIXED.
for f in "$PREFIX"-pov*.mp4; do
  [ -f "$f" ] || continue
  case "$f" in *.rot.mp4) continue ;; esac
  rot="$(ffprobe -v error -select_streams v:0 -show_entries stream_side_data=rotation -of csv=p=0 "$f" | head -1)"
  if [ "$rot" != "90" ]; then echo "  $(basename "$f"): rotation ${rot:-none}, left as is"; continue; fi
  tmp="${f%.mp4}.rot.mp4"
  if ffmpeg -v error -display_rotation:v:0 -90 -i "$f" -map 0 -c copy -movflags +faststart -y "$tmp" \
     && [ "$(ffprobe -v error -select_streams v:0 -show_entries stream_side_data=rotation -of csv=p=0 "$tmp" | head -1)" = "-90" ]; then
    mv "$tmp" "$f"; fixed=$((fixed + 1)); echo "  $(basename "$f"): rotation 90 -> -90"
  else
    rm -f "$tmp"; echo "  $(basename "$f"): rewrite FAILED, left as is" >&2
  fi
done
[ "$fixed" -gt 0 ] || { echo "  nothing to fix"; exit 0; }
SHOTS_DIR="$(dirname "$PREFIX")" "$REPO_ROOT/ops/take.sh" "$NAME" --recompose "$STAMP"
