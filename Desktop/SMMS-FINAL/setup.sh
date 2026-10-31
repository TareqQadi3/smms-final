#!/bin/bash
# Starts the full SMMS stack. Safe to re-run: keeps existing data volumes.
# Runs automatically in GitHub Codespaces (see /.devcontainer/devcontainer.json).
set -euo pipefail
SCRIPT_PATH="${BASH_SOURCE[0]}"
cd "$(dirname "$SCRIPT_PATH")"

log() { echo "[smms-setup] $*"; }

# ── Deployment guard (reversible: revert this block and the devcontainer flag) ─────────────
# --auto              automatic start (devcontainer postStartCommand): never deploys changed code
#                     over an existing database; it only starts the already-built stack.
# --backup-file PATH  a dump created by scripts/backup-db.sh (needs PATH.verified, < 24 h old).
# SMMS_ALLOW_UNBACKED_DEPLOY=1  explicit override for throwaway/dev databases only.
AUTO_MODE=0
BACKUP_FILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --auto) AUTO_MODE=1; shift ;;
    --backup-file) BACKUP_FILE="${2:-}"; shift 2 || { echo "--backup-file needs a path" >&2; exit 2; } ;;
    -h|--help) sed -n '/^# ── Deployment guard/,/^# SMMS_ALLOW_UNBACKED_DEPLOY/p' "$SCRIPT_PATH"; exit 0 ;;
    *) echo "Unknown option: $1 (try --help)" >&2; exit 2 ;;
  esac
done

log "Waiting for Docker..."
for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && break; sleep 2; done
docker info >/dev/null 2>&1 || { log "ERROR: Docker is not available"; exit 1; }

# ── Secrets: generated locally into the untracked .env, never printed ──────────
# Never enable shell tracing here: the generated values must not reach any log.
set +x

PLACEHOLDER='__GENERATED_BY_SETUP__'
# SHA-256 of JWT_SECRET values that were published in this repository (old .env.codespaces,
# application-dev.yml, old .env.example). An existing .env that still holds one is rotated.
KNOWN_WEAK_JWT_SHA256="
fb2bfb4bbb10b378aaa5e1452184bf9ee94a1dd7f393ec0254e7f5a1757821d9
d3f7b4a5ab3de13335ad7e4e0a29c3c62d83333a4007b1a59d05c634a8f00180
5549ff03a50c89b9733836415bcc71a20100fe1fa6e54235f6b42c4be13fdd29
4976e0e10a8ab028b4ca8e13372c01af4f91f12779f3b027a99fe689771cf8b7"

env_value() {  # env_value KEY -> prints the raw value of KEY from .env (empty if absent)
  # last occurrence wins, like docker compose
  grep "^$1=" .env | tail -1 | cut -d= -f2- | tr -d '\r' || true
}

set_env_value() {  # set_env_value KEY VALUE ; the value travels via the environment, not argv
  local tmp
  tmp="$(umask 077; mktemp .env.XXXXXX)"
  K="$1" V="$2" awk 'BEGIN{k=ENVIRON["K"]; v=ENVIRON["V"]}
    index($0, k "=") == 1 { if (!done) print k "=" v; done=1; next } { print }' .env > "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" .env
}

compose_project() {
  local name=""
  if [ -n "${COMPOSE_PROJECT_NAME:-}" ]; then
    name="$COMPOSE_PROJECT_NAME"
  elif command -v jq >/dev/null 2>&1; then
    name="$(docker compose config --format json 2>/dev/null | jq -r '.name // empty' 2>/dev/null || true)"
  fi
  if [ -z "$name" ]; then
    name="$(basename "$PWD" | tr 'A-Z' 'a-z' | tr -cd 'a-z0-9_-')"
  fi
  echo "$name"
}

db_volume_exists() {
  local project
  project="$(compose_project)"
  [ -n "$(docker volume ls -q \
      --filter "label=com.docker.compose.project=$project" \
      --filter "label=com.docker.compose.volume=postgres_data" 2>/dev/null)" ]
}

# Fail-closed detection used by the guard: any postgres_data volume (whatever the compose project is
# called), the fixed smms_postgres container, or an unanswerable docker query all count as "a
# database may exist". A false positive only asks for a backup; a false negative would skip it.
db_present_anywhere() {
  local vols
  vols="$(docker volume ls -q 2>/dev/null)" || return 0
  if printf '%s\n' "$vols" | grep -Eq '(^|_)postgres_data$'; then return 0; fi
  if docker container inspect smms_postgres >/dev/null 2>&1; then return 0; fi
  db_volume_exists
}

abort_existing_volume() {
  log "ERROR: an existing database volume was found but .env has no database password."
  log "The DB password is bound to that volume; restore your previous .env (or the DB_PASSWORD"
  log "it contained). setup.sh will not generate a new one for an existing database."
  exit 1
}

# ── Guard: never migrate/deploy over an existing database without a verified backup ────────
DEPLOY_MARKER=".smms-deployed-commit"   # untracked; records the state last deployed by this script

# State = HEAD commit + hash of uncommitted changes to tracked files. Without git it is "unknown",
# which never equals a recorded state, so the guard then always asks for a verified backup.
# (New untracked files are not part of the state.)
current_commit() {
  local head dirty
  head="$(git rev-parse HEAD 2>/dev/null)" || { echo unknown; return; }
  dirty="$(git diff HEAD 2>/dev/null | sha256sum | cut -c1-16)"
  echo "${head}+${dirty}"
}

backup_is_verified() {  # backup_is_verified FILE -> 0 only for a fresh, checksum-matching verified backup
  local f="$1" m="$1.verified" want got
  [ -n "$f" ] && [ -s "$f" ] && [ -s "$m" ] || return 1
  find "$m" -mmin -1440 2>/dev/null | grep -q . || return 1
  want="$(sed -n 's/^sha256=//p' "$m" | head -1)"
  got="$(sha256sum "$f" | cut -d' ' -f1)"
  [ -n "$want" ] && [ "$want" = "$got" ]
}

if db_present_anywhere; then
  deployed="$(cat "$DEPLOY_MARKER" 2>/dev/null || echo none)"
  state_now="$(current_commit)"
  if [ "$state_now" = "unknown" ] || [ "$state_now" != "$deployed" ]; then
    if [ "$AUTO_MODE" = "1" ]; then
      log "GUARD: an existing database was found and the code changed since the last deployment."
      log "GUARD: automatic start will NOT build or migrate. Starting the existing stack as it is."
      log "GUARD: to deploy: bash scripts/backup-db.sh, then: bash setup.sh --backup-file <dump>"
      # `start` only starts existing stopped containers; unlike `up` it never recreates them from a
      # newer image or changed config, so it cannot migrate anything.
      docker compose start || log "GUARD: containers missing; run the manual steps above."
      exit 0
    elif [ "${SMMS_ALLOW_UNBACKED_DEPLOY:-0}" = "1" ]; then
      log "WARNING: SMMS_ALLOW_UNBACKED_DEPLOY=1 - deploying over an existing database WITHOUT a verified backup."
    elif backup_is_verified "$BACKUP_FILE"; then
      log "Verified backup accepted: $(basename "$BACKUP_FILE")"
      grep -q "^uploads=skipped" "$BACKUP_FILE.verified" && log "WARNING: this backup does NOT include the uploads (attachments)."
    else
      log "ERROR: refusing to deploy. An existing database volume was found and no fresh verified backup was given."
      log "  1) bash scripts/backup-db.sh        (creates, verifies and rehearses a restore)"
      log "  2) bash setup.sh --backup-file <the .dump path it prints>"
      log "Nothing was changed."
      exit 1
    fi
  fi
fi

if [ ! -f .env ]; then
  if db_volume_exists; then abort_existing_volume; fi
  log "Creating .env from .env.codespaces with freshly generated secrets"
  (umask 077; cp .env.codespaces .env)
  chmod 600 .env
fi

if [ "$(env_value DB_PASSWORD)" = "$PLACEHOLDER" ]; then
  if db_volume_exists; then abort_existing_volume; fi
  set_env_value DB_PASSWORD "$(openssl rand -hex 24)"
  log "Generated DB_PASSWORD"
fi
if [ "$(env_value REDIS_PASSWORD)" = "$PLACEHOLDER" ]; then
  set_env_value REDIS_PASSWORD "$(openssl rand -hex 24)"
  log "Generated REDIS_PASSWORD"
fi

jwt_now="$(env_value JWT_SECRET)"
if [ -z "$jwt_now" ] || [ "$jwt_now" = "$PLACEHOLDER" ]; then
  set_env_value JWT_SECRET "$(openssl rand -base64 48 | tr -d '\n')"
  log "Generated JWT_SECRET"
elif printf '%s\n' "$KNOWN_WEAK_JWT_SHA256" | grep -qx "$(printf '%s' "$jwt_now" | sha256sum | cut -d' ' -f1)"; then
  set_env_value JWT_SECRET "$(openssl rand -base64 48 | tr -d '\n')"
  log "JWT secret rotated (was the public test value); all users must sign in again"
fi
unset jwt_now
# DB and Redis passwords of an existing install are never changed here (see docs: manual rotation).

log "Building and starting services (first run takes several minutes)..."
docker compose up -d --build
# nginx reads its bind-mounted config only at start; compose does not recreate it when only the file changed
docker compose restart nginx >/dev/null 2>&1 || true

log "Waiting for backend health..."
for _ in $(seq 1 90); do
  if docker exec smms_backend wget -qO- http://localhost:8080/actuator/health 2>/dev/null | grep -q '"UP"'; then
    break
  fi
  sleep 4
done

docker compose ps
if ! docker exec smms_backend wget -qO- http://localhost:8080/actuator/health 2>/dev/null | grep -q '"UP"'; then
  log "ERROR: backend did not become healthy. Last log lines:"
  docker logs --tail 40 smms_backend
  exit 1
fi

code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost/login || true)
state_final="$(current_commit)"
if [ "$state_final" != "unknown" ]; then printf '%s\n' "$state_final" > "$DEPLOY_MARKER"; fi
log "Backend: UP | Login page via nginx: HTTP $code"
log "Open the PORTS tab and use the address for port 80 (SMMS App)."
