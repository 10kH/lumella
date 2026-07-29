#!/usr/bin/env bash
# Nightly backup of luma's irreplaceable state.
#
# Why: luma-api holds 332 users, 392 learning sessions, learner states and uploaded images
# in a single SQLite file plus a media directory, and until 2026-07-29 there was no backup
# of any of it. A disk failure would have ended the project's accumulated data — none of it
# is in git, and none of it can be regenerated.
#
# Uses SQLite's own `.backup`, not `cp`: copying a live database can capture a torn write and
# yield a file that only fails when you finally need it. The integrity check afterwards means
# a broken backup is noticed on the night it happens rather than during a restore.
#
# Restore: stop luma-api, replace data/luma.db and data/uploads from a snapshot, start again.
#   ops/launchd/manage.sh luma-uninstall
#   tar xzf <snapshot>.tar.gz -C <luma-api>/
#   ops/launchd/manage.sh luma-install
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LUMA_API_DIR="$(cd "$SCRIPT_DIR/../../luma/luma-api" && pwd)"
DB="$LUMA_API_DIR/data/luma.db"
BACKUP_ROOT="${LUMA_BACKUP_DIR:-$HOME/Backups/luma}"
KEEP_DAYS="${LUMA_BACKUP_KEEP_DAYS:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"
WORK="$BACKUP_ROOT/.work-$STAMP"

[ -f "$DB" ] || { echo "ERROR: $DB not found" >&2; exit 1; }
mkdir -p "$BACKUP_ROOT" "$WORK"
trap 'rm -rf "$WORK"' EXIT

# Consistent snapshot of a live database.
sqlite3 "$DB" ".backup '$WORK/luma.db'"

# A backup that silently corrupts is worse than none — verify before keeping it.
CHECK="$(sqlite3 "$WORK/luma.db" 'PRAGMA integrity_check;' | head -1)"
if [ "$CHECK" != "ok" ]; then
  echo "ERROR: integrity_check failed: $CHECK" >&2
  exit 1
fi

# Uploaded images are referenced by image_analyses rows; a DB without them restores broken.
if [ -d "$LUMA_API_DIR/data/uploads" ]; then
  cp -R "$LUMA_API_DIR/data/uploads" "$WORK/uploads"
fi
if [ -d "$LUMA_API_DIR/data/raw-media" ]; then
  cp -R "$LUMA_API_DIR/data/raw-media" "$WORK/raw-media"
fi

ARCHIVE="$BACKUP_ROOT/luma-$STAMP.tar.gz"
tar czf "$ARCHIVE" -C "$WORK" .
SIZE="$(du -h "$ARCHIVE" | cut -f1)"

# Keep the window bounded so this never fills the disk unattended.
find "$BACKUP_ROOT" -maxdepth 1 -name 'luma-*.tar.gz' -mtime "+$KEEP_DAYS" -delete 2>/dev/null || true

COUNT="$(find "$BACKUP_ROOT" -maxdepth 1 -name 'luma-*.tar.gz' | wc -l | tr -d ' ')"
echo "$(date '+%Y-%m-%d %H:%M:%S') backup ok: $ARCHIVE ($SIZE), integrity=ok, retained=$COUNT"
