#!/usr/bin/env bash
# The shoot-morning check for the AI FESTA demo video: one command, a line per thing that has
# failed before, and a verdict. Runs ops/preflight.sh (Mac internet, token service, luma tunnel,
# adb, app) first, then what the film in particular depends on:
#
#   - ETRI Tango answers (every mt.etri.re.kr endpoint returned 503 'Server Offline' on the
#     morning of 9/29 and came back at 16:4x) and a cafe line is actually routed to it
#   - the glasses: Wi-Fi with internet, battery, temperature, free space, media volume
#   - lumella relaunched with the learner record wiped, READY, and the coach connected
#
#   ops/shoot-preflight.sh             # everything, then relaunch lumella with --reset
#   ops/shoot-preflight.sh --no-reset  # keep the learner record (between the two takes)
#   ops/shoot-preflight.sh --no-topic  # no preset topic: the tutor opens with "오늘은 어떤 얘기할까요?"
#   ops/shoot-preflight.sh --topic "어제 친구와 한 일"   # a preset topic (booth/operator)
#   ops/shoot-preflight.sh --wifi-adb  # also switch adb to Wi-Fi so the cable can come off
#
# Exit status 0 only if nothing FAILED. WARN lines do not stop the shoot but say what to watch.
set -uo pipefail
cd "$(dirname "$0")/.."
RESET=1; WIFI_ADB=0; TOPIC_ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --no-reset) RESET=0 ;;
    --wifi-adb) WIFI_ADB=1 ;;
    --topic) TOPIC_ARGS=(--topic "${2:?--topic needs a topic}"); shift ;;
    --no-topic) TOPIC_ARGS=(--no-topic) ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done
PKG=com.woolab.lumella
FAILS=0; WARNS=0
ok()   { printf '  OK    %s\n' "$*"; }
warn() { printf '  WARN  %s\n' "$*"; WARNS=$((WARNS + 1)); }
fail() { printf '  FAIL  %s\n' "$*"; FAILS=$((FAILS + 1)); }

echo "== connectivity chain (ops/preflight.sh)"
if ./ops/preflight.sh >/tmp/shoot-preflight-chain.txt 2>&1; then
  ok "Mac internet, token service, luma address, adb, app"
else
  fail "ops/preflight.sh — $(tail -1 /tmp/shoot-preflight-chain.txt)"
  sed 's/^/        /' /tmp/shoot-preflight-chain.txt | tail -8
fi

DEV="${ANDROID_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device" {print $1}')}"
if [ "$(printf '%s\n' "$DEV" | grep -c .)" != 1 ]; then
  # Cable plugged back in to charge while adb is also on Wi-Fi: name the one to use.
  fail "more than one device (found: $(printf '%s' "$DEV" | tr '\n' ' ')) — run with ANDROID_SERIAL=<one of them>, or unplug one"
  echo "verdict: NOT READY"; exit 1
fi
export ANDROID_SERIAL="$DEV"
A="adb -s $DEV"
# A take whose --stop never ran leaves the device-side screen loop recording until the disk fills.
if [ "$($A shell pgrep screenrecord 2>/dev/null | tr -d '\r' | grep -c .)" != 0 ] && ! ls /tmp/lumella-take-*.stamp >/dev/null 2>&1; then
  $A shell 'for f in /sdcard/*-s1.t; do touch "${f%-s1.t}.stop"; done; pkill -INT screenrecord' >/dev/null 2>&1
  warn "a screen recording from an unfinished take was still running; stopped it"
fi

echo "== ETRI Tango"
t0=$(python3 -c 'import time; print(time.time())')
code=$(curl -s -o /dev/null -m 30 -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
       -d '{"content":"안녕하세요"}' https://mt.etri.re.kr/tango_generate)
dt=$(python3 -c "import time; print(round(time.time()-$t0,1))")
if [ "$code" = 200 ]; then ok "tango_generate 200 in ${dt}s"
else fail "tango_generate HTTP $code — Tango is down; the ETRI Tango moments (F2 line 4, F3 line 8) cannot be shot. Ask 이동혁 (ETRI)"; fi

echo "== a cafe line through luma"
route=$(python3 - <<'PY' 2>&1
import json, time, urllib.request
props = dict(l.strip().split('=', 1) for l in open('local.properties') if '=' in l and not l.startswith('#'))
cfg = json.loads(urllib.request.urlopen(urllib.request.Request(
    props['lumella.tokenServiceBaseUrl'].rstrip('/') + '/v1/config',
    headers={'X-Lumella-Local-Token': props['lumella.localToken']}), timeout=15).read())
base = cfg['lumaBaseUrl'].rstrip('/')
def post(path, body, token=None):
    h = {'Content-Type': 'application/json', 'X-Luma-Client': 'glasses'}
    if token: h['Authorization'] = 'Bearer ' + token
    r = urllib.request.urlopen(urllib.request.Request(base + path, data=json.dumps(body).encode(), headers=h,
                                                      method='POST'), timeout=60)
    return json.loads(r.read() or b'{}')
login = post('/v1/auth/session', {'provider': 'email', 'email': props['lumella.brainEmail'],
                                  'password': props['lumella.brainPassword'], 'client': 'glasses'})
t0 = time.time()
j = post('/v1/orchestrator/turn', {'orchestratorSessionId': None, 'surface': 'glasses', 'deviceId': None,
                                    'content': '여기 카페 분위기 좋아요', 'responseMode': 'coach',
                                    'attachments': [], 'metadata': {}}, login['accessToken'])
print(f"{j.get('selectedProvider')}/{j.get('selectedRoute')} fallback={j.get('fallbackUsed')} {time.time()-t0:.1f}s")
PY
)
case "$route" in
  etri/*fallback=False*) ok "coach turn: $route" ;;
  *fallback=True*)       fail "coach turn fell back: $route — Tango answered badly or late through luma" ;;
  openai/*)              warn "coach turn went to GPT: $route (the cafe line usually goes to Tango; try once more)" ;;
  *)                     fail "coach turn failed: $(printf '%s' "$route" | tail -1)" ;;
esac

echo "== the glasses"
if $A shell 'ping -c1 -W3 8.8.8.8' >/dev/null 2>&1; then
  ok "internet on the glasses ($($A shell 'cmd wifi status' 2>/dev/null | tr -d '\r' | grep -oE 'connected to "[^"]+"' | head -1))"
else
  $A shell svc wifi enable >/dev/null 2>&1; sleep 8
  if $A shell 'ping -c1 -W3 8.8.8.8' >/dev/null 2>&1; then warn "Wi-Fi was off; switched it on"
  else fail "no internet on the glasses — join the shoot network (Settings on the glasses, or the phone hotspot)"; fi
fi
bat=$($A shell dumpsys battery 2>/dev/null | tr -d '\r' | awk -F': ' '/  level:/ {print $2}')
temp=$($A shell dumpsys battery 2>/dev/null | tr -d '\r' | awk -F': ' '/temperature:/ {print $2/10}')
if [ "${bat:-0}" -ge 60 ]; then ok "battery ${bat}%, ${temp}°C"
elif [ "${bat:-0}" -ge 30 ]; then warn "battery ${bat}% — enough for a take or two; charge between takes"
else fail "battery ${bat}% — charge first"; fi
therm=$($A shell dumpsys thermalservice 2>/dev/null | tr -d '\r' | awk -F': ' '/Thermal Status/ {print $2; exit}')
[ "${therm:-0}" -le 1 ] && ok "thermal status ${therm:-0}" || warn "thermal status $therm — let the glasses cool before a take"
free=$($A shell df /data 2>/dev/null | tr -d '\r' | awk 'NR==2 {print int($4/1024/1024)}')
[ "${free:-0}" -ge 3 ] && ok "free space ${free} GB (a take is ~45 MB a minute)" || fail "only ${free:-0} GB free on the glasses"
vol=$($A shell 'cmd media_session volume --stream 3 --get' 2>/dev/null | tr -d '\r' | grep -oE 'volume is [0-9]+' | grep -oE '[0-9]+')
if [ "${vol:-0}" -ge 8 ]; then ok "media volume ${vol}/15"
else $A shell "cmd media_session volume --stream 3 --set 10" >/dev/null 2>&1; warn "media volume was ${vol:-?}/15 — set to 10 (the wearer must hear the tutor)"; fi

echo "== lumella"
$A shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
if [ "$RESET" = 1 ]; then ANDROID_SERIAL="$DEV" ./ops/launch-lumella.sh --reset ${TOPIC_ARGS[@]+"${TOPIC_ARGS[@]}"} >/dev/null 2>&1
else ANDROID_SERIAL="$DEV" ./ops/launch-lumella.sh ${TOPIC_ARGS[@]+"${TOPIC_ARGS[@]}"} >/dev/null 2>&1; fi
pid=""; ready=""; brain=""
for _ in $(seq 1 30); do
  sleep 2
  pid=$($A shell pidof $PKG 2>/dev/null | tr -d '\r')
  [ -n "$pid" ] || continue
  log=$($A logcat -d -v brief 2>/dev/null | grep "( *$pid)")
  printf '%s' "$log" | grep -q 'status=READY' && ready=1
  printf '%s' "$log" | grep -q 'brain ready' && brain=1
  [ -n "$ready" ] && [ -n "$brain" ] && break
done
[ -n "$ready" ] && ok "voice READY" || fail "voice not READY after 60 s — ops/preflight.sh, then relaunch"
topic_line="$($A logcat -d -v brief 2>/dev/null | grep "( *$pid)" | grep -oE '대화 주제: .*|대화 주제 없음.*' | tail -1)"
if [ "${TOPIC_ARGS[0]:-}" = "--no-topic" ]; then
  case "$topic_line" in
    "대화 주제 없음"*) ok "no preset topic — the tutor will ask what to talk about" ;;
    *) fail "a topic is still set (${topic_line}) — ops/launch-lumella.sh --no-topic" ;;
  esac
elif [ "${#TOPIC_ARGS[@]}" -gt 0 ]; then
  case "$topic_line" in
    "대화 주제: ${TOPIC_ARGS[1]}"*) ok "topic session: ${TOPIC_ARGS[1]}" ;;
    *) fail "topic not in effect (${topic_line:-no log line}) — ops/launch-lumella.sh --topic \"${TOPIC_ARGS[1]}\"" ;;
  esac
else
  ok "${topic_line:-topic: unknown}"
fi
[ -n "$brain" ] && ok "coach connected" || fail "coach not connected after 60 s — no Tango/GPT line, no habit memory; check luma"
[ "$RESET" = 1 ] && ok "learner record wiped (take 1 starts fresh)" || ok "learner record kept (take 2 continues take 1)"

if [ "$WIFI_ADB" = 1 ]; then
  echo "== adb over Wi-Fi"
  ip=$($A shell ip -4 addr show wlan0 2>/dev/null | tr -d '\r' | awk '/inet / {split($2,a,"/"); print a[1]}')
  $A tcpip 5555 >/dev/null 2>&1; sleep 4
  if adb connect "$ip:5555" 2>&1 | grep -q connected; then
    ok "adb on $ip:5555 — unplug the cable now; take.sh uses whichever one device is connected"
  else
    # macOS only lets a process reach the local network once it is allowed; an adb server
    # started before that stays blocked ('No route to host' while ping works). Restart it.
    adb kill-server; adb start-server >/dev/null 2>&1; sleep 2
    adb connect "$ip:5555" 2>&1 | grep -q connected && ok "adb on $ip:5555 (after restarting the adb server) — unplug the cable now" \
      || fail "adb over Wi-Fi failed ($ip) — the network may isolate clients; shoot tethered or use the phone hotspot"
  fi
fi

echo
if [ "$FAILS" = 0 ]; then echo "verdict: READY ($WARNS warning(s))"; exit 0
else echo "verdict: NOT READY — $FAILS failure(s), $WARNS warning(s)"; exit 1; fi
