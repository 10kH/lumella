#!/usr/bin/env bash
# Build, install, verify, and cut the cable — in one command.
#
# Why this exists: the recurring mistake recorded in docs/MACBOOK-SETUP.md is running
# assembleDebug and walking away without `adb install`, then filming with the OLD apk. The two
# steps are never useful apart, so they are one command here. It also does the three things
# that are easy to forget when the glasses have just been plugged in:
#
#   1. WAKE the screen. A dark screen swallows input and later `am broadcast` commands.
#   2. Detect a SIGNATURE mismatch (INSTALL_FAILED_UPDATE_INCOMPATIBLE) and say what it means,
#      instead of leaving a raw adb error. This happens when the installed build was signed by
#      a different machine's ~/.android/debug.keystore — e.g. the mac mini built it and this
#      MacBook is now pushing. There is no way to re-sign in place; the app must be uninstalled,
#      which also drops its local data. The wearer's history lives in luma on the mac mini, not
#      on the device, so that loss is recoverable — but it is the user's call, so we stop and ask.
#   3. Switch to WIRELESS adb, because a cable in frame ruins a take.
#
#   ops/install-glasses.sh              # build + install over whatever adb is connected
#   ops/install-glasses.sh --wireless   # ... then hand off to tcp:5555 and tell you the IP
#   ops/install-glasses.sh --force      # uninstall first when signatures disagree (destructive)
#
# JAVA_HOME must point at a real JDK 17 (see AGENTS.md — Homebrew's openjdk@17 is keg-only,
# so /usr/libexec/java_home cannot find it and Gradle's jvmToolchain(17) fails).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="com.woolab.lumella"
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"

WIRELESS=""
FORCE=""
for arg in "$@"; do
  case "$arg" in
    --wireless) WIRELESS=1 ;;
    --force) FORCE=1 ;;
    -h|--help) sed -n '2,23p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

# One device only. With both a USB and a stale tcp:5555 entry attached, adb refuses every
# command with "more than one device" — confusing right when you are trying to leave.
# NOTE: macOS ships bash 3.2, which has no `mapfile`. Keep this loop portable.
DEVICES=()
while IFS= read -r line; do
  [ -n "$line" ] && DEVICES+=("$line")
done < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ "${#DEVICES[@]}" -eq 0 ]; then
  echo "ERROR: no device. Plug the glasses in over USB." >&2
  echo "  If the screen shows 'Allow USB debugging?', tap Always allow — this Mac's adb key" >&2
  echo "  is only trusted after that prompt is accepted once." >&2
  exit 1
fi
if [ "${#DEVICES[@]}" -gt 1 ]; then
  echo "ERROR: ${#DEVICES[@]} devices attached: ${DEVICES[*]}" >&2
  echo "  Disconnect one, or drop a stale wireless entry with: adb disconnect <ip>:5555" >&2
  exit 1
fi
SERIAL="${DEVICES[0]}"
echo "device: $SERIAL"

echo "== build =="
( cd "$REPO_ROOT" && ./gradlew :app:assembleDebug --console=plain -q )
[ -f "$APK" ] || { echo "ERROR: apk missing after build: $APK" >&2; exit 1; }

# A blank local.properties token builds fine and fails only at runtime, as TOKEN-FAIL on the
# wearer's face. Cheaper to catch it here.
if ! grep -qE '^lumella\.localToken=.+' "$REPO_ROOT/local.properties" 2>/dev/null; then
  echo "WARNING: lumella.localToken is empty in local.properties — the app will show TOKEN-FAIL." >&2
fi

echo "== wake =="
adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true

echo "== install =="
set +e
OUT="$(adb -s "$SERIAL" install -r "$APK" 2>&1)"
RC=$?
set -e
echo "$OUT"

if [ $RC -ne 0 ] && grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match" <<<"$OUT"; then
  echo
  echo "SIGNATURE MISMATCH — the installed app was signed by a different machine." >&2
  echo "  This Mac's debug key:" >&2
  keytool -list -v -keystore "$HOME/.android/debug.keystore" -storepass android \
    -alias androiddebugkey 2>/dev/null | grep "SHA256:" >&2 || true
  echo "  Re-signing in place is impossible. Either copy the OTHER machine's" >&2
  echo "  ~/.android/debug.keystore here, or uninstall (drops on-device data; luma keeps the" >&2
  echo "  conversation history):" >&2
  echo "      ops/install-glasses.sh --force" >&2
  if [ -n "$FORCE" ]; then
    echo "  --force given; uninstalling and retrying." >&2
    adb -s "$SERIAL" uninstall "$PKG" || true
    adb -s "$SERIAL" install "$APK"
  else
    exit 1
  fi
elif [ $RC -ne 0 ]; then
  exit $RC
fi

echo "== verify =="
adb -s "$SERIAL" shell dumpsys package "$PKG" \
  | awk '/versionName|lastUpdateTime|firstInstallTime/ {gsub(/^ +/,""); print "  " $0}' | sort -u

if [ -n "$WIRELESS" ]; then
  echo "== wireless =="
  # Persists across reboots via service.adb.tcp.port, so this is normally a one-time step —
  # but the IP is DHCP and changes with every network, so it is always re-printed.
  adb -s "$SERIAL" tcpip 5555 >/dev/null
  sleep 2
  IP="$(adb -s "$SERIAL" shell ip route 2>/dev/null | awk '/wlan0/ {print $NF; exit}')"
  if [ -z "$IP" ]; then
    echo "  no wlan0 address — glasses Wi-Fi is off (it switches itself off after a reboot)." >&2
    echo "  Turn Wi-Fi on, join the same network as this Mac, then re-run with --wireless." >&2
    exit 1
  fi
  adb connect "$IP:5555" >/dev/null
  echo "  connected: $IP:5555   (unplug the cable now)"
  echo "  record:  adb -s $IP:5555 shell screenrecord --time-limit 180 /sdcard/take1.mp4"
fi

echo "done."
