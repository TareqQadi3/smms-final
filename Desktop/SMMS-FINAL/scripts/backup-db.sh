#!/bin/bash
# Creates and VERIFIES a backup of the SMMS PostgreSQL database. Read-only on the database.
#   1. pg_dump -Fc inside the running postgres container -> ~/smms-backups/db-<UTC>.dump
#   2. checks the file (non-empty, PGDMP header, readable by pg_restore -l)
#   3. rehearses a restore into a throwaway container (no published port, random password)
#      and compares row counts with the live database
#   4. only if everything matches, writes db-<UTC>.dump.verified (sha256, counts, commit)
# `bash setup.sh --backup-file <dump>` accepts a backup only when that .verified file exists.
# Also copies .env and the uploads volume (best effort). Secrets are never printed.
#
# Overrides (for tests): SMMS_PG_CONTAINER, SMMS_ENV_FILE, SMMS_BACKUP_DIR, SMMS_SKIP_UPLOADS=1
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
set +x

PG="${SMMS_PG_CONTAINER:-smms_postgres}"
ENV_FILE="${SMMS_ENV_FILE:-.env}"
OUT_DIR="${SMMS_BACKUP_DIR:-$HOME/smms-backups}"
TABLES="tenants users work_orders ticket_history attachments notifications centers regions control_rooms"

log() { echo "[smms-backup] $*"; }
fail() { log "ERROR: $*"; exit 1; }

env_value() { grep -m1 "^$1=" "$ENV_FILE" | cut -d= -f2- | tr -d '\r' || true; }

command -v docker >/dev/null 2>&1 || fail "docker not found"
[ -f "$ENV_FILE" ] || fail "$ENV_FILE not found (run from the SMMS-FINAL directory of the deployed stack)"
DB_USER="$(env_value DB_USER)"; DB_NAME="$(env_value DB_NAME)"
[ -n "$DB_USER" ] && [ -n "$DB_NAME" ] || fail "DB_USER/DB_NAME missing in $ENV_FILE"
[ "$(docker inspect -f '{{.State.Running}}' "$PG" 2>/dev/null || echo false)" = "true" ] \
  || fail "container $PG is not running"

psql_live() { docker exec "$PG" psql -U "$DB_USER" -d "$DB_NAME" -Atc "$1"; }

count_sql() {  # one line "table=count" per existing table
  local t out=""
  for t in $TABLES; do
    out="$out
SELECT '$t=' || count(*) FROM $t UNION ALL"
  done
  echo "${out%UNION ALL}"
}

umask 077
mkdir -p "$OUT_DIR"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
DUMP="$OUT_DIR/db-$TS.dump"
SQL="$(count_sql)"

log "Counting rows (before)"
BEFORE="$(psql_live "$SQL")"

log "Dumping to $DUMP"
docker exec "$PG" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc > "$DUMP"
[ -s "$DUMP" ] || { rm -f "$DUMP"; fail "dump is empty"; }
[ "$(head -c 5 "$DUMP")" = "PGDMP" ] || fail "dump has no PGDMP header"
docker exec -i "$PG" pg_restore -l < "$DUMP" | grep -q "TABLE DATA" || fail "pg_restore cannot list table data"

log "Counting rows (after)"
AFTER="$(psql_live "$SQL")"

IMAGE="$(docker inspect -f '{{.Config.Image}}' "$PG")"
RC="smms_restore_check_$TS"
RPW="$(openssl rand -hex 16)"
cleanup() { docker rm -f "$RC" >/dev/null 2>&1 || true; }
trap cleanup EXIT

log "Restore rehearsal in throwaway container $RC (no published port)"
docker run -d --name "$RC" -e POSTGRES_PASSWORD="$RPW" "$IMAGE" >/dev/null
for _ in $(seq 1 60); do
  docker exec "$RC" pg_isready -U postgres >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$RC" pg_isready -U postgres >/dev/null 2>&1 || fail "rehearsal database did not start"
docker exec "$RC" createdb -U postgres restorecheck
# role-related warnings are expected with --no-owner; table data errors are not
docker exec -i "$RC" pg_restore --no-owner -U postgres -d restorecheck < "$DUMP" 2>/tmp/smms-restore-$TS.err || true
RESTORED="$(docker exec "$RC" psql -U postgres -d restorecheck -Atc "$SQL")"

check_counts() {
  local line t b a r ok=1
  while IFS= read -r line; do
    t="${line%%=*}"
    b="$(printf '%s\n' "$BEFORE" | sed -n "s/^$t=//p")"
    a="$(printf '%s\n' "$AFTER" | sed -n "s/^$t=//p")"
    r="${line#*=}"
    # writes may happen while the dump runs: accept before <= restored <= after
    if [ -z "$r" ] || [ "$r" -lt "$b" ] || [ "$r" -gt "$a" ]; then
      log "MISMATCH $t: before=$b restored=$r after=$a"; ok=0
    else
      log "ok       $t: restored=$r"
    fi
  done <<< "$RESTORED"
  return $((1 - ok))
}
check_counts || fail "restored row counts do not match the live database; backup NOT verified (see /tmp/smms-restore-$TS.err)"
rm -f "/tmp/smms-restore-$TS.err"

SHA="$(sha256sum "$DUMP" | cut -d' ' -f1)"
{
  echo "sha256=$SHA"
  echo "created_utc=$TS"
  echo "git_commit=$(git rev-parse HEAD 2>/dev/null || echo unknown)"
  printf '%s\n' "$RESTORED" | sed 's/^/rows_/'
} > "$DUMP.verified"

# Best effort extras (not part of the verification marker)
cp "$ENV_FILE" "$OUT_DIR/env-$TS" && chmod 600 "$OUT_DIR/env-$TS"   # holds secrets: keep private
if [ "${SMMS_SKIP_UPLOADS:-0}" != "1" ]; then
  PROJECT="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$PG" 2>/dev/null || true)"
  UVOL="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT" \
            --filter "label=com.docker.compose.volume=uploads_data" 2>/dev/null | head -1)"
  if [ -n "$UVOL" ]; then
    docker run --rm --entrypoint tar -v "$UVOL":/d:ro -v "$OUT_DIR":/b "$IMAGE" czf "/b/uploads-$TS.tgz" -C /d . \
      && log "Uploads archived: uploads-$TS.tgz" || log "WARNING: uploads archive failed"
  else
    log "WARNING: uploads volume not found; attachments are NOT in this backup"
  fi
fi

log "VERIFIED backup: $DUMP"
log "Next: bash setup.sh --backup-file $DUMP"
