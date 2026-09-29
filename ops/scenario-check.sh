#!/usr/bin/env bash
# Rehearses the AI FESTA demo video's lines on the glasses without a speaker in the room
# (aaai27 DEMO-SCENARIO-AIFESTA26.md, scenes B and C), and records what the screen shows after
# each one: the tutor's subtitle, the coach line (which engine coached: SLM-TANGO or LLM-GPT),
# and the corner (coach luna while a habit is on record).
#
# Each line goes in twice, in the order a spoken turn has them: first the learner's words to the
# realtime model (DEBUG_SAY — this opens the turn and the tutor answers it), then the transcript
# (DEBUG_EVENT input_transcript — the luma coach and the slow layer see it, for that turn). The
# other order filed each transcript under the previous turn, and the coach line, which only
# describes the current turn, was dropped every time (first run, 2026-09-29). The photo turn in scene B cannot be
# rehearsed this way; it needs a wearer and a real scene.
#
#   ops/scenario-check.sh <label>      # writes artifacts/booth/scenario-<label>.txt

set -uo pipefail
cd "$(dirname "$0")/.."
LABEL="${1:?usage: ops/scenario-check.sh <label>}"
PKG=com.woolab.lumella
DEV="${ANDROID_SERIAL:-A06B4A043084773}"
OUT="artifacts/booth/scenario-$LABEL.txt"
adbs() { adb -s "$DEV" "$@"; }

LINES=(
  # B: recast and expansion
  "여기 과일 시장이에요. 아주머니가 사과 물어봐요"
  "사과 세 개 샀어요"
  "저는 딸기 좋아요"
  # C: in-topic chat (TANGO), an off-topic question (GPT), then one habit three times and fixed
  "여기 과일이 진짜 싱싱해요"
  "근데 BTS 콘서트 티켓은 어떻게 사요?"
  "어제도 친구가 시장에서 만났어요"
  "오늘 아침에 빵이 먹었어요"
  "내일은 영화가 볼 거예요"
  "어제 친구를 만났어요"
  "오늘 아침에 빵을 먹었어요"
  "내일은 영화를 볼 거예요"
  # The diagnosis is re-examined every third turn: formed at turn 9 (slips at 6-8), it clears at
  # turn 12 once turns 9-11 were clean.
  "주말에도 친구를 만날 거예요"
)

# launch-lumella.sh waits for "Ready", but hands-free lumella shows "Listening..." once it is
# connected and listening (log: status=READY, then 연속 청취 시작) until the first turn. Both mean
# ready here.
./ops/launch-lumella.sh --reset >/dev/null 2>&1
ready=""
for _ in $(seq 1 10); do
  st="$(./ops/screen-dump.sh 2>/dev/null | awk '/L +tvStatus/{print; exit}' | sed 's/.*"\(.*\)".*/\1/')"
  case "$st" in Ready|Listening...) ready=1; break ;; esac
  sleep 3
done
[ -n "$ready" ] || { echo "lumella not Ready (status: ${st:-none})" >&2; exit 1; }
{
  echo "# ops/scenario-check.sh $LABEL — $(date '+%Y-%m-%d %H:%M') — lumella $(git rev-parse --short HEAD)"
  i=0
  for line in "${LINES[@]}"; do
    i=$((i+1))
    echo
    echo "== $i  학습자: $line"
    adbs shell "am broadcast -p $PKG -a $PKG.DEBUG_SAY --es text '$line'" >/dev/null
    sleep 1
    adbs shell "am broadcast -p $PKG -a $PKG.DEBUG_EVENT --es json 'input_transcript:$line'" >/dev/null
    sleep 16
    # The left eye is enough: both eyes carry the same text.
    ./ops/screen-dump.sh 2>/dev/null | grep -E '^\s+L\s+tv' | sed 's/^/   /'
  done
} | tee "$OUT"
echo "wrote $OUT"
