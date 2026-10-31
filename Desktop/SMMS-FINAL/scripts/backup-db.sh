#!/bin/bash
# Creates and VERIFIES a backup of the SMMS PostgreSQL database. Read-only on the database.
#   1. pg_dump -Fc inside the running postgres container -> ~/smms-backups/db-<UTC>.dump
#   2. checks the file (non-empty, PGDMP header, readable by pg_restore -l)
#   3. archives the uploads volume (attachments) -> uploads-<UTC>.tgz; failure is FATAL
#      (set SMMS_SKIP_UPLOADS=1 only if you knowingly have no attachments to protect)
#   4. rehearses a restore into a throwaway container (no published port, random password)
#      and compares the row count of EVERY public table with the live database
#   5. only if everything matches, writes db-<UTC>.dump.verified (sha256, counts, commit)
# `bash setup.sh --backup-file <dump>` accepts a backup only when that .verified file exists.
# Also copies .env (holds secrets: keep it private). Secrets are never printed.
#
# Overrides (for tests): SMMS_PG_CONTAINER, SMMS_ENV_FILE, SMMS_BACKUP_DIR, SMMS_SKIP_UPLOADS=1
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
set +x

PG="${SMMS_PG_CONTAINER:-smms_postgres}"
ENV_FILE="${SMMS_ENV_FILE:-.env}"
OUT_DIR="${SMMS_BACKUP_DIR:-$HOME/smms-backups}"

log() { echo "[smms-backup] $*"; }
fail() { log "ERROR: $*"; exit 1; }

env_value() { grep "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- | tr -d '\r' || true; }   # last wins, like compose

command -v docker >/dev/null 2>&1 || fail "docker not found"
[ -f "$ENV_FILE" ] || fail "$ENV_FILE not found (run from the SMMS-FINAL directory of the deployed stack)"
DB_USER="$(env_value DB_USER)"; DB_NAME="$(env_value DB_NAME)"
[ -n "$DB_USER" ] && [ -n "$DB_NAME" ] || fail "DB_USER/DB_NAME missing in $ENV_FILE"
case "$DB_USER$DB_NAME" in *[!A-Za-z0-9_-]*) fail "DB_USER/DB_NAME contain unexpected characters" ;; esac
[ "$(docker inspect -f '{{.State.Running}}' "$PG" 2>/dev/null || echo false)" = "true" ] \
  || fail "container $PG is not running"

psql_live() { docker exec "$PG" psql -U "$DB_USER" -d "$DB_NAME" -Atc "$1"; }

TABLES="$(psql_live "select tablename from pg_tables where schemaname='public' order by 1")"
[ -n "$TABLES" ] || fail "no tables found in $DB_NAME (wrong database?)"
NTABLES="$(printf '%s\n' "$TABLES" | wc -l)"

count_sql() {  # one line "table=count" per public table
  local t sql=""
  for t in $TABLES; do
    case "$t" in *[!a-z0-9_]*) fail "unexpected table name" ;; esac
    sql="${sql}${sql:+ UNION ALL }SELECT '$t=' || count(*) FROM public.\"$t\""
  done
  echo "$sql"
}

umask 077
mkdir -p "$OUT_DIR"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
DUMP="$OUT_DIR/db-$TS.dump"
SQL="$(count_sql)"

log "Counting rows (before) in $NTABLES tables"
BEFORE="$(psql_live "$SQL")"

log "Dumping to $DUMP"
docker exec "$PG" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc > "$DUMP"
[ -s "$DUMP" ] || { rm -f "$DUMP"; fail "dump is empty"; }
[ "$(head -c 5 "$DUMP")" = "PGDMP" ] || fail "dump has no PGDMP header"
LISTING="$(docker exec -i "$PG" pg_restore -l < "$DUMP")"   # no early-exit pipe: avoids SIGPIPE under pipefail
printf '%s\n' "$LISTING" | grep "TABLE DATA" >/dev/null || fail "pg_restore cannot list table data"

log "Counting rows (after)"
AFTER="$(psql_live "$SQL")"

IMAGE="$(docker inspect -f '{{.Config.Image}}' "$PG")"

# Attachments: part of the backup, not optional.
UPLOADS_LINE="uploads=skipped"
if [ "${SMMS_SKIP_UPLOADS:-0}" != "1" ]; then
  PROJECT="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$PG" 2>/dev/null || true)"
  UVOL="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT" \
            --filter "label=com.docker.compose.volume=uploads_data" 2>/dev/null | head -1)"
  [ -n "$UVOL" ] || fail "uploads volume not found: attachments would NOT be backed up (SMMS_SKIP_UPLOADS=1 to override knowingly)"
  UP="$OUT_DIR/uploads-$TS.tgz"
  # tar runs as root inside the container (to read the volume); the archive is then handed to the
  # calling user with mode 600 so it can be copied off the machine.
  docker run --rm --entrypoint sh -e UID_="$(id -u)" -e GID_="$(id -g)" \
    -v "$UVOL":/d:ro -v "$OUT_DIR":/b "$IMAGE" \
    -c 'umask 077; tar czf "/b/uploads-'"$TS"'.tgz" -C /d . && chown "$UID_:$GID_" "/b/uploads-'"$TS"'.tgz"' \
    || fail "uploads archive failed"
  [ -s "$UP" ] || fail "uploads archive is empty or missing"
  tar tzf "$UP" >/dev/null || fail "uploads archive is not readable"
  UPLOADS_LINE="uploads_sha256=$(sha256sum "$UP" | cut -d' ' -f1)"
  log "Uploads archived: uploads-$TS.tgz"
fi

RC="smms_restore_check_$TS"
RPW="$(openssl rand -hex 16)"
ERRF="$(mktemp)"
cleanup() { docker rm -f "$RC" >/dev/null 2>&1 || true; rm -f "$ERRF"; }
trap cleanup EXIT

log "Restore rehearsal in throwaway container $RC (no published port)"
docker run -d --name "$RC" -e POSTGRES_PASSWORD="$RPW" "$IMAGE" >/dev/null
# The image first runs a temporary socket-only server; TCP answers only from the final start.
for _ in $(seq 1 90); do
  docker exec "$RC" psql -h 127.0.0.1 -U postgres -d postgres -Atc 'select 1' >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$RC" psql -h 127.0.0.1 -U postgres -d postgres -Atc 'select 1' >/dev/null 2>&1 \
  || fail "rehearsal database did not start"
docker exec "$RC" createdb -h 127.0.0.1 -U postgres restorecheck
docker exec -i "$RC" pg_restore --no-owner -h 127.0.0.1 -U postgres -d restorecheck < "$DUMP" 2>"$ERRF" || true
NERR="$(grep -c 'error:' "$ERRF" || true)"
[ "$NERR" = "0" ] || log "WARNING: pg_restore reported $NERR error line(s) (role/extension notices are expected; counts decide)"
RESTORED="$(docker exec "$RC" psql -h 127.0.0.1 -U postgres -d restorecheck -Atc "$SQL")" \
  || fail "restored database is missing at least one table; backup NOT verified"

check_counts() {
  local line t b a r n=0 ok=1 num='^[0-9]+$'
  while IFS= read -r line; do
    t="${line%%=*}"; r="${line#*=}"
    b="$(printf '%s\n' "$BEFORE" | sed -n "s/^$t=//p")"
    a="$(printf '%s\n' "$AFTER" | sed -n "s/^$t=//p")"
    n=$((n + 1))
    if ! [[ "$r" =~ $num && "$b" =~ $num && "$a" =~ $num ]]; then
      log "MISMATCH $t: non-numeric count (before='$b' restored='$r' after='$a')"; ok=0; continue
    fi
    # writes may happen while the dump runs: accept before <= restored <= after
    if [ "$r" -lt "$b" ] || [ "$r" -gt "$a" ]; then
      log "MISMATCH $t: before=$b restored=$r after=$a"; ok=0
    else
      log "ok       $t: restored=$r"
    fi
  done <<< "$RESTORED"
  [ "$n" = "$NTABLES" ] || { log "MISMATCH: compared $n of $NTABLES tables"; ok=0; }
  return $((1 - ok))
}
check_counts || fail "restored row counts do not match the live database; backup NOT verified"

SHA="$(sha256sum "$DUMP" | cut -d' ' -f1)"
{
  echo "sha256=$SHA"
  echo "created_utc=$TS"
  echo "git_commit=$(git rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "$UPLOADS_LINE"
  echo "tables=$NTABLES"
  printf '%s\n' "$RESTORED" | sed 's/^/rows_/'
} > "$DUMP.verified"

cp "$ENV_FILE" "$OUT_DIR/env-$TS" && chmod 600 "$OUT_DIR/env-$TS"   # holds secrets: keep private

log "VERIFIED backup: $DUMP"
[ "$UPLOADS_LINE" = "uploads=skipped" ] && log "WARNING: uploads were skipped; attachments are NOT in this backup"
log "Next: bash setup.sh --backup-file $DUMP"
