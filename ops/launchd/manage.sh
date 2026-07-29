#!/usr/bin/env bash
# Mac-resident LaunchAgent manager for the lumella realtime token-service.
#
# Why: the glasses mint ephemeral OpenAI realtime credentials from this service.
# Run by hand it dies with the shell, and the glasses then show TOKEN-FAIL —
# a symptom that looks like an API/credential problem but is just a dead local
# process (observed repeatedly, 2026-07). launchd keeps it up across reboots.
#
# Secrets stay in token-service/.env.local (gitignored); this script and the
# plist carry none.
#
# Usage:
#   ops/launchd/manage.sh install     # render + load + start + health-check
#   ops/launchd/manage.sh uninstall   # stop + unload + remove the LaunchAgent
#   ops/launchd/manage.sh restart     # kickstart the running service
#   ops/launchd/manage.sh status      # launchctl state + /healthz
#   ops/launchd/manage.sh logs        # tail the service logs
#
#   ops/launchd/manage.sh backup-install    # nightly backup of luma's data
#   ops/launchd/manage.sh backup-now        # take one right now
#   ops/launchd/manage.sh luma-install      # run luma-api itself (the coach engine)
#   ops/launchd/manage.sh luma-status
#   ops/launchd/manage.sh tunnel-install    # keep luma-api reachable off-LAN
#   ops/launchd/manage.sh tunnel-status
#   ops/launchd/manage.sh tunnel-uninstall
set -euo pipefail

LABEL="com.woolab.lumella.token-service"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TEMPLATE="$SCRIPT_DIR/$LABEL.plist.template"
PLIST_DST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG_DIR="$REPO_ROOT/tmp/token-service"
GUI="gui/$(id -u)"
PORT="8788"
HOST="127.0.0.1"

render() {
  local node_bin path_val
  node_bin="$(command -v node || true)"
  [ -n "$node_bin" ] || { echo "ERROR: node not found on PATH" >&2; exit 1; }
  path_val="$(dirname "$node_bin"):/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"
  mkdir -p "$LOG_DIR" "$(dirname "$PLIST_DST")"
  sed -e "s#__NODE_BIN__#${node_bin}#g" \
      -e "s#__REPO_ROOT__#${REPO_ROOT}#g" \
      -e "s#__PATH__#${path_val}#g" \
      -e "s#__LOG_DIR__#${LOG_DIR}#g" \
      "$TEMPLATE" > "$PLIST_DST"
  echo "rendered $PLIST_DST (node=$node_bin)"
}

stop_port() {
  local pids
  pids="$(lsof -ti "tcp:$PORT" -sTCP:LISTEN 2>/dev/null || true)"
  if [ -n "$pids" ]; then
    echo "stopping process(es) on :$PORT -> $pids"
    kill $pids 2>/dev/null || true
    sleep 1
  fi
}

health() {
  sleep 2
  if curl -fsS -m 5 "http://$HOST:$PORT/healthz" >/dev/null 2>&1; then
    echo "OK: http://$HOST:$PORT/healthz is up"
  else
    echo "WARN: /healthz not responding yet — check: $0 logs" >&2
    return 1
  fi
}

case "${1:-}" in
  install)
    [ -f "$REPO_ROOT/token-service/.env.local" ] || {
      echo "ERROR: token-service/.env.local missing (needs OPENAI_API_KEY + LUMELLA_LOCAL_TOKEN)" >&2
      exit 1
    }
    render
    stop_port
    launchctl bootout "$GUI/$LABEL" 2>/dev/null || true
    launchctl bootstrap "$GUI" "$PLIST_DST"
    launchctl kickstart -k "$GUI/$LABEL"
    health
    ;;
  uninstall)
    launchctl bootout "$GUI/$LABEL" 2>/dev/null || true
    rm -f "$PLIST_DST"
    echo "removed $PLIST_DST"
    ;;
  restart)
    launchctl kickstart -k "$GUI/$LABEL"
    health
    ;;
  status)
    launchctl print "$GUI/$LABEL" 2>/dev/null | grep -E "state|pid|last exit" || echo "not loaded"
    curl -fsS -m 5 "http://$HOST:$PORT/healthz" 2>/dev/null || echo "healthz: unreachable"
    ;;
  logs)
    tail -n 40 "$LOG_DIR"/token-service.*.log 2>/dev/null || echo "no logs at $LOG_DIR"
    ;;
  backup-install)
    BLABEL="com.woolab.lumella.luma-backup"
    BTEMPLATE="$SCRIPT_DIR/$BLABEL.plist.template"
    BPLIST="$HOME/Library/LaunchAgents/$BLABEL.plist"
    command -v sqlite3 >/dev/null || { echo "ERROR: sqlite3 not found" >&2; exit 1; }
    mkdir -p "$LOG_DIR" "$(dirname "$BPLIST")"
    sed -e "s#__REPO_ROOT__#${REPO_ROOT}#g" \
        -e "s#__PATH__#/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin#g" \
        -e "s#__HOME__#${HOME}#g" \
        -e "s#__LOG_DIR__#${LOG_DIR}#g" \
        "$BTEMPLATE" > "$BPLIST"
    launchctl bootout "$GUI/$BLABEL" 2>/dev/null || true
    launchctl bootstrap "$GUI" "$BPLIST"
    echo "installed $BLABEL — runs daily at 04:30; run now with: $0 backup-now"
    ;;
  backup-now)
    bash "$REPO_ROOT/ops/backup-luma.sh"
    ;;
  backup-uninstall)
    launchctl bootout "$GUI/com.woolab.lumella.luma-backup" 2>/dev/null || true
    rm -f "$HOME/Library/LaunchAgents/com.woolab.lumella.luma-backup.plist"
    echo "removed the backup agent"
    ;;
  luma-install)
    # luma-api itself — the tunnel is useless if nothing is listening behind it.
    LLABEL="com.woolab.lumella.luma-api"
    LTEMPLATE="$SCRIPT_DIR/$LLABEL.plist.template"
    LPLIST="$HOME/Library/LaunchAgents/$LLABEL.plist"
    LUMA_API_DIR="$(cd "$REPO_ROOT/../luma/luma-api" 2>/dev/null && pwd)"
    [ -n "$LUMA_API_DIR" ] || { echo "ERROR: luma-api directory not found next to this repo" >&2; exit 1; }
    VENV_PY="$LUMA_API_DIR/.venv/bin/python"
    [ -x "$VENV_PY" ] || { echo "ERROR: $VENV_PY missing — create the venv first" >&2; exit 1; }
    mkdir -p "$LOG_DIR" "$(dirname "$LPLIST")"
    sed -e "s#__VENV_PYTHON__#${VENV_PY}#g" \
        -e "s#__LUMA_API_DIR__#${LUMA_API_DIR}#g" \
        -e "s#__PATH__#$(dirname "$VENV_PY"):/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin#g" \
        -e "s#__HOME__#${HOME}#g" \
        -e "s#__LOG_DIR__#${LOG_DIR}#g" \
        "$LTEMPLATE" > "$LPLIST"
    # Free the port: a hand-started uvicorn would keep launchd's copy crash-looping.
    pids="$(lsof -ti tcp:8010 -sTCP:LISTEN 2>/dev/null || true)"
    [ -n "$pids" ] && { echo "stopping existing luma-api on :8010 -> $pids"; kill $pids 2>/dev/null || true; sleep 2; }
    launchctl bootout "$GUI/$LLABEL" 2>/dev/null || true
    launchctl bootstrap "$GUI" "$LPLIST"
    sleep 4
    curl -fsS -m 5 http://127.0.0.1:8010/v1/health >/dev/null 2>&1 \
      && echo "OK: luma-api healthy on :8010" \
      || echo "WARN: not healthy yet — check $LOG_DIR/luma-api.err.log" >&2
    ;;
  luma-uninstall)
    launchctl bootout "$GUI/com.woolab.lumella.luma-api" 2>/dev/null || true
    rm -f "$HOME/Library/LaunchAgents/com.woolab.lumella.luma-api.plist"
    echo "removed the luma-api agent"
    ;;
  luma-status)
    launchctl print "$GUI/com.woolab.lumella.luma-api" 2>/dev/null | grep -E "state|pid" || echo "not loaded"
    curl -fsS -m 5 http://127.0.0.1:8010/v1/health 2>/dev/null || echo "healthz: unreachable"
    ;;
  tunnel-install)
    # Public tunnel for luma-api so the glasses keep the coach outside the LAN.
    TLABEL="com.woolab.lumella.luma-tunnel"
    TTEMPLATE="$SCRIPT_DIR/$TLABEL.plist.template"
    TPLIST="$HOME/Library/LaunchAgents/$TLABEL.plist"
    command -v cloudflared >/dev/null || { echo "ERROR: cloudflared not found" >&2; exit 1; }
    command -v vercel >/dev/null || { echo "ERROR: vercel CLI not found (needed to republish the URL)" >&2; exit 1; }
    mkdir -p "$LOG_DIR" "$(dirname "$TPLIST")"
    node_bin="$(command -v node)"
    sed -e "s#__REPO_ROOT__#${REPO_ROOT}#g" \
        -e "s#__PATH__#$(dirname "$node_bin"):$(dirname "$(command -v cloudflared)"):/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin#g" \
        -e "s#__HOME__#${HOME}#g" \
        -e "s#__LOG_DIR__#${LOG_DIR}#g" \
        "$TTEMPLATE" > "$TPLIST"
    launchctl bootout "$GUI/$TLABEL" 2>/dev/null || true
    launchctl bootstrap "$GUI" "$TPLIST"
    echo "installed $TLABEL — the tunnel URL is republished to Vercel on each start; watch $LOG_DIR/luma-tunnel.out.log"
    ;;
  tunnel-uninstall)
    launchctl bootout "$GUI/com.woolab.lumella.luma-tunnel" 2>/dev/null || true
    rm -f "$HOME/Library/LaunchAgents/com.woolab.lumella.luma-tunnel.plist"
    echo "removed the luma tunnel agent"
    ;;
  tunnel-status)
    launchctl print "$GUI/com.woolab.lumella.luma-tunnel" 2>/dev/null | grep -E "state|pid" || echo "not loaded"
    tail -n 5 "$LOG_DIR"/luma-tunnel.out.log 2>/dev/null || echo "no tunnel log yet"
    ;;
  *)
    sed -n '12,18p' "$0"
    exit 1
    ;;
esac
