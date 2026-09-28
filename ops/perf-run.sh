#!/usr/bin/env bash
# One reproducible load run of the booth scenario, measured.
#
#   ops/perf-run.sh <label> <none|audio|pov>
#
# Relaunches lumella clean, starts the chosen recording mode, drives the six booth sentences
# through DEBUG_EVENT (the real realtime API answers, so playback load is real), stops, and
# writes artifacts/perf/<label>.json with:
#   cpu        per-process and top-thread CPU%, sampled every 2s during the run (top -H)
#   playback   per-response AudioTrack underruns, audio time vs wall time, write() blocking
#   capture    per-5s-window late reads (not scheduled), lost reads (buffer overran), worst cycle
#   ttfa       per-turn time to first tutor audio
#   thermal    CPU zone temperatures and frequencies at start and end
#   files      POV, screen and the composed FINAL: codec, resolution, effective fps (ffprobe);
#              learner and tutor WAVs: length and share of loud samples
#   diagnosis  ruleGap after turn 3 and after turn 6 (the slow path must not regress)
# and keeps the raw top samples (<label>.top.txt) and the app's log (<label>.logcat.txt) beside it.
#
# STRESS=N adds N busy loops on the device for the whole run: the load of a worse day (the
# heavier POV baseline ran ~67 points above the lighter one on identical settings), applied on
# purpose, to see whether the audio path holds with less headroom than a quiet run leaves.
#
# Why a script and not a checklist: the POV stutter was described for two weeks as "끊긴다"
# without a number. Every optimisation after this one is judged by the diff of two of these.
set -euo pipefail

LABEL="${1:?usage: ops/perf-run.sh <label> <none|audio|pov>}"
MODE="${2:?usage: ops/perf-run.sh <label> <none|audio|pov>}"
case "$MODE" in none|audio|pov) ;; *) echo "mode must be none|audio|pov" >&2; exit 2 ;; esac

cd "$(dirname "$0")/.."
PKG=com.woolab.lumella
DEV="${ANDROID_SERIAL:-A06B4A043084773}"
OUT_DIR=artifacts/perf
WORK="$(mktemp -d /tmp/perf-$LABEL.XXXXXX)"
mkdir -p "$OUT_DIR"
trap 'rm -rf "$WORK"' EXIT

adbs() { adb -s "$DEV" "$@"; }

SENTENCES=(
  "어제 친구가 만났어요"
  "오늘 아침에 빵이 먹었어요"
  "내일 영화가 볼 거예요"
  "어제 친구를 만났어요"
  "오늘 아침에 빵을 먹었어요"
  "내일 영화를 볼 거예요"
)
TURN_GAP_S=18

thermal() {
  adbs shell 'for z in /sys/class/thermal/thermal_zone*; do t=$(cat $z/type 2>/dev/null); case "$t" in cpu-*) echo "$t $(cat $z/temp)";; esac; done; echo freq $(cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq | tr "\n" " ")' 2>/dev/null
}

# The first DNS lookup from a freshly started process sometimes fails on this headset even
# while the shell resolves the same host (seen 9/28 on CCC2_WLAN). What is measured here is
# recording load, not the network, so a dead session gets up to two relaunches before giving up.
adbs logcat -G 8M >/dev/null 2>&1 || true
for attempt in 1 2 3; do
  echo "[$LABEL] launch --reset (attempt $attempt)"
  adbs logcat -c
  ./ops/launch-lumella.sh --reset >/dev/null 2>&1 || true
  sleep 7
  # `|| true` inside both: under set -e a dead app (pidof fails) or no status line yet (grep
  # finds nothing) would end the script here instead of reaching the retry.
  PID="$(adbs shell pidof $PKG | tr -d '\r' || true)"
  STATUS="$( [ -n "$PID" ] && adbs logcat -d -v brief | grep "( *$PID)" | grep -oE 'status=[A-Z-]+' | tail -1 || true )"
  [ "$STATUS" = "status=READY" ] && break
  echo "[$LABEL] not READY ($STATUS)" >&2
done
if [ "$STATUS" != "status=READY" ]; then
  echo "lumella not READY after 3 launches — network? refusing to measure a dead session" >&2
  exit 1
fi
thermal > "$WORK/thermal-start.txt"

STRESS="${STRESS:-0}"
if [ "$STRESS" -gt 0 ]; then
  adbs shell "for i in \$(seq $STRESS); do yes > /dev/null 2>&1 & done" >/dev/null 2>&1
  trap 'adbs shell pkill -x yes >/dev/null 2>&1; rm -rf "$WORK"' EXIT
  echo "[$LABEL] stress: $(adbs shell pgrep -x yes | tr -d '\r' | grep -c .) busy loops"
fi

TAKE="perf-$LABEL"
case "$MODE" in
  audio) ./ops/take.sh "$TAKE" --start --audio >/dev/null 2>&1 ;;
  pov)   ./ops/take.sh "$TAKE" --start --pov   >/dev/null 2>&1 ;;
esac

# CPU sampler: toybox top reports 0.0 for everything on its first iteration (no interval yet),
# so each sample is a 2-iteration run over 1s and only the second block is kept. The header's
# idle figure gives whole-device load; the rows give the top 40 threads by CPU.
( while :; do
    adbs shell 'top -H -b -n 2 -d 1 -m 40 -s 3 -o PID,TID,%CPU,CMD,NAME 2>/dev/null' \
      | awk -v t="$(date +%s)" '/^Threads:/{b++} b==2{ if (/^Threads:/) print "=== " t; print }'
    sleep 1
  done ) > "$WORK/top.txt" 2>/dev/null &
SAMPLER=$!

turn() {
  adbs shell am broadcast -p $PKG -a $PKG.DEBUG_EVENT --es json "speech_started" >/dev/null
  sleep 1
  adbs shell am broadcast -p $PKG -a $PKG.DEBUG_EVENT --es json "speech_stopped" >/dev/null
  sleep 1
  adbs shell am broadcast -p $PKG -a $PKG.DEBUG_EVENT --es json "'input_transcript:$1'" >/dev/null
}
i=0
for s in "${SENTENCES[@]}"; do
  i=$((i+1)); echo "[$LABEL] turn $i: $s"
  turn "$s"
  sleep "$TURN_GAP_S"
  if [ $i -eq 3 ]; then
    adbs shell "run-as $PKG cat files/learner-state.json" > "$WORK/state-3.json" 2>/dev/null || true
  fi
done
sleep 6
adbs shell "run-as $PKG cat files/learner-state.json" > "$WORK/state-6.json" 2>/dev/null || true

kill $SAMPLER 2>/dev/null || true
wait $SAMPLER 2>/dev/null || true
[ "$STRESS" -gt 0 ] && adbs shell pkill -x yes >/dev/null 2>&1

STOP_OUT=""
case "$MODE" in
  audio|pov) STOP_OUT="$(./ops/take.sh "$TAKE" --stop 2>&1 || true)" ;;
esac
thermal > "$WORK/thermal-end.txt"
adbs logcat -d -v time | grep "( *$PID)" > "$WORK/logcat.txt" || true
cp "$WORK/top.txt" "$OUT_DIR/$LABEL.top.txt" 2>/dev/null || true

# Recorded files, if any
# Match take.sh's labelled lines, not file-name fragments: a label like "baseline-pov" puts
# "-pov" into EVERY file name of the take, and the first version of this picked the screen
# recording as the POV. POV may come in segments; all are kept.
POV_FILE="$(echo "$STOP_OUT" | awk '$1=="pov" && $2 ~ /\.mp4$/ {print $2}' | tr '\n' ' ' | sed 's/ $//' || true)"
SCREEN_FILE="$(echo "$STOP_OUT" | awk '$1=="screen" {print $2; exit}' || true)"
TUTOR_FILE="$(echo "$STOP_OUT" | awk '$1=="tutor" {print $2; exit}' || true)"
LEARNER_FILE="$(echo "$STOP_OUT" | awk '$1=="learner" {print $2; exit}' || true)"
FINAL_FILE="$(echo "$STOP_OUT" | awk '$1=="FINAL" {print $2; exit}' || true)"

python3 - "$LABEL" "$MODE" "$WORK" "$OUT_DIR/$LABEL.json" "$POV_FILE" "$SCREEN_FILE" "$TUTOR_FILE" "$LEARNER_FILE" "$FINAL_FILE" <<'PY'
import json, re, sys, subprocess, os, statistics
label, mode, work, out, pov, screen, tutor, learner, final = sys.argv[1:10]

def rd(n):
    try: return open(os.path.join(work, n), encoding='utf-8', errors='replace').read()
    except FileNotFoundError: return ''

# --- CPU: per process name and per thread, mean and peak over samples
samples=[]; busy=[]; cur=None
for line in rd('top.txt').splitlines():
    if line.startswith('=== '):
        cur={}; samples.append(cur); continue
    if cur is None: continue
    m=re.match(r'\s*(\d+)%cpu.*?(\d+)%idle', line)
    if m:
        busy.append(int(m.group(1)) - int(m.group(2))); continue
    # "  PID   TID %CPU CMD             NAME" — CMD is the thread name, 15 chars, may hold spaces
    m=re.match(r'\s*(\d+)\s+(\d+)\s+([\d.]+)\s(.{15})\s+(\S.*)$', line)
    if not m: continue
    cpu=float(m.group(3)); thread=m.group(4).strip(); name=m.group(5).strip()
    if name == 'top':
        # The sampler itself: top -H walks ~1,900 threads and costs 15-20% of a core, which the
        # header's busy figure includes. Take it out of the whole-device number too, not just
        # the rows — before 2026-09-28 it was only dropped from the rows.
        if busy: busy[-1] -= cpu
        continue
    cur[('proc', name)] = cur.get(('proc', name), 0.0) + cpu
    cur[('thr', name + ' / ' + thread)] = cur.get(('thr', name + ' / ' + thread), 0.0) + cpu
def agg(kind, top_n):
    keys={k for s in samples for k in s if k[0]==kind}
    rows=[]
    for k in keys:
        vals=[s.get(k,0.0) for s in samples]
        rows.append({'name':k[1],'meanPct':round(statistics.mean(vals),1),'peakPct':round(max(vals),1)})
    return sorted(rows, key=lambda r:-r['meanPct'])[:top_n]
total=busy

# --- Playback per response
log=rd('logcat.txt')
playback=[]
# minLeadMs/maxGapMs arrived 2026-09-28: a negative lead means the data came late (network or
# server), a positive one with underruns means this device did not run the writer in time.
for m in re.finditer(r'perf: playback underruns=(-?\d+) audioMs=(\d+) wallMs=(\d+) chunks=(\d+) maxWriteMs=(\d+) sumWriteMs=(\d+) maxTapMs=(\d+) bufferFrames=(-?\d+)(?: minLeadMs=(-?\d+) maxGapMs=(\d+))?(?: minLeadAtChunk=(\d+))?', log):
    g=m.groups(); u,a,w,c,mw,sw,mt,bf=map(int,g[:8])
    playback.append({'underruns':u,'audioMs':a,'wallMs':w,'stretch':round(w/a,2) if a else None,
                     'chunks':c,'maxWriteMs':mw,'sumWriteMs':sw,'maxTapMs':mt,'bufferFrames':bf,
                     'minLeadMs':int(g[8]) if g[8] is not None else None,'maxGapMs':int(g[9]) if g[9] is not None else None,
                     'minLeadAtChunk':int(g[10]) if g[10] is not None else None})
capture=[]
# lostReads/chunkMs arrived with the larger capture buffer (2026-09-28); before it the buffer WAS
# one chunk, so a late read and a lost read were the same event.
for m in re.finditer(r'perf: capture reads=(\d+) lateReads=(\d+)(?: lostReads=(\d+))? maxCycleMs=(\d+) maxHandleMs=(\d+)(?: chunkMs=(\d+))? bufferMs=(\d+)', log):
    r,l,lo,mc,mh,ch,b=m.groups()
    capture.append({'reads':int(r),'lateReads':int(l),'lostReads':int(lo) if lo is not None else int(l),
                    'maxCycleMs':int(mc),'maxHandleMs':int(mh),'chunkMs':int(ch) if ch is not None else int(b),'bufferMs':int(b)})
ttfa=[int(x) for x in re.findall(r'튜터 발화 시작 \(TTFA (-?\d+)ms\)', log)]
# Turns the server's VAD really ended, as opposed to the harness's injected ones. A quiet-room run
# must have none: room sound taken for speech changes the run (9/28 at home it switched the tutor's
# language and closed the app). lumella logs "turn end (VAD)" for both, and "debug: 음성 종료 주입"
# only for the injected ones.
realVad=max(0, len(re.findall(r'turn end \(VAD\)', log)) - len(re.findall(r'debug: 음성 종료 주입', log)))

# --- Files
def probe(p):
    if not p or not os.path.exists(p): return None
    try:
        j=json.loads(subprocess.run(['ffprobe','-v','error','-select_streams','v:0','-count_frames',
            '-show_entries','stream=codec_name,width,height,nb_read_frames,avg_frame_rate:format=duration',
            '-of','json',p],capture_output=True,text=True,timeout=120).stdout)
        st=j['streams'][0]; d=float(j['format']['duration']); n=int(st.get('nb_read_frames') or 0)
        return {'path':p,'codec':st.get('codec_name'),'width':st.get('width'),'height':st.get('height'),
                'durationS':round(d,1),'frames':n,'effectiveFps':round(n/d,1) if d else None}
    except Exception as e:
        return {'path':p,'error':str(e)[:120]}
def voiced(p):
    if not p or not os.path.exists(p): return None
    import wave, struct
    w=wave.open(p); n=w.getnframes(); s=struct.unpack('<%dh'%n, w.readframes(n))
    return {'path':p,'durationS':round(n/w.getframerate(),1),'voicedPct':round(100*sum(1 for v in s if abs(v)>1000)/max(n,1),1)}

def rg(n):
    try: return json.loads(rd(n)).get('ruleGap')
    except Exception: return 'unreadable'
def stateSummary(n):
    try: d=json.loads(rd(n))
    except Exception: return None
    return {'ruleGap':d.get('ruleGap'),'grammarErrors':len(d.get('grammarErrors',[])),
            'turns':len(d.get('turnHistory',[])),'lastConsolidatedTurnId':d.get('lastConsolidatedTurnId'),
            'errorSpans':[e.get('span') for e in d.get('grammarErrors',[])]}
slowWarn=re.findall(r'W/lumella.*?(\w+ (?:call|parse) failed at turn \d+[^\n]{0,80})', log)
# The per-role routing log went with RoutingPedagogyClient (0215078). The record's own
# lastConsolidatedTurnId is the authoritative witness that a diagnosis call landed.

def summarise_pb(pb):
    if not pb: return None
    return {'responses':len(pb),'underrunsTotal':sum(max(p['underruns'],0) for p in pb),
            'responsesWithUnderrun':sum(1 for p in pb if p['underruns']>0),
            'stretchMax':max((p['stretch'] or 0) for p in pb),
            'stretchMedian':statistics.median([p['stretch'] for p in pb if p['stretch']]) if any(p['stretch'] for p in pb) else None,
            'maxWriteMs':max(p['maxWriteMs'] for p in pb),'maxTapMs':max(p['maxTapMs'] for p in pb)}
def summarise_cap(cp):
    if not cp: return None
    return {'windows':len(cp),'lateReadsTotal':sum(c['lateReads'] for c in cp),'lostReadsTotal':sum(c['lostReads'] for c in cp),
            'maxCycleMs':max(c['maxCycleMs'] for c in cp),'chunkMs':cp[0]['chunkMs'],'bufferMs':cp[0]['bufferMs']}

result={'label':label,'mode':mode,'stress':int(os.environ.get('STRESS','0')),
  'summary':{'cpuTotalMeanPct':round(statistics.mean(total),1) if total else None,
             'cpuTotalPeakPct':round(max(total),1) if total else None,
             'playback':summarise_pb(playback),'capture':summarise_cap(capture),
             'ttfaMs':{'n':len(ttfa),'median':statistics.median(ttfa) if ttfa else None,'max':max(ttfa) if ttfa else None},
             'ruleGapAfter3':rg('state-3.json'),'ruleGapAfter6':rg('state-6.json'),
             'realVadTurns':realVad},
  'cpu':{'samples':len(samples),'processes':agg('proc',10),'threads':agg('thr',15)},
  'slowPath':{'after3':stateSummary('state-3.json'),'after6':stateSummary('state-6.json'),
              'warnings':slowWarn[:10]},
  'playback':playback,'capture':capture,'ttfaMs':ttfa,
  'thermal':{'start':rd('thermal-start.txt').strip().splitlines(),'end':rd('thermal-end.txt').strip().splitlines()},
  'files':{'pov':[probe(p) for p in pov.split()] if pov else None,'screen':probe(screen),'tutorWav':voiced(tutor),'learnerWav':voiced(learner),
         'final':probe(final)}}
json.dump(result, open(out,'w'), ensure_ascii=False, indent=1)
# CameraX logs a Status event per encoded frame — 80% of a POV run's log. A run of them is kept
# as its first and last line: the first is when frames actually started reaching the file (the
# take clock hangs on it), the last when they stopped.
def collapse_status(text):
    out=[]; run=[]
    for line in text.splitlines():
        if 'Sending VideoRecordEvent Status' in line: run.append(line); continue
        if run: out += [run[0]] + ([f'    ... {len(run)-2} more Status ...', run[-1]] if len(run) > 1 else []); run=[]
        out.append(line)
    if run: out += [run[0]] + ([f'    ... {len(run)-2} more Status ...', run[-1]] if len(run) > 1 else [])
    return '\n'.join(out) + '\n'
open(out.replace('.json','.logcat.txt'),'w').write(collapse_status(log))
s=result['summary']
print(f"[{label}] cpu mean {s['cpuTotalMeanPct']}% peak {s['cpuTotalPeakPct']}% | playback {s['playback']} | capture {s['capture']} | ttfa {s['ttfaMs']} | ruleGap@3 {'set' if s['ruleGapAfter3'] else s['ruleGapAfter3']} @6 {s['ruleGapAfter6']}")
PY
echo "[$LABEL] -> $OUT_DIR/$LABEL.json"
