#!/bin/bash
# Starts the full SMMS stack. Safe to re-run: keeps existing data volumes.
# Runs automatically in GitHub Codespaces (see /.devcontainer/devcontainer.json).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

log() { echo "[smms-setup] $*"; }

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
  grep -m1 "^$1=" .env | cut -d= -f2- | tr -d '\r' || true
}

set_env_value() {  # set_env_value KEY VALUE ; the value travels via the environment, not argv
  local tmp
  tmp="$(umask 077; mktemp .env.XXXXXX)"
  K="$1" V="$2" awk 'BEGIN{k=ENVIRON["K"]; v=ENVIRON["V"]}
    index($0, k "=") == 1 && !done { print k "=" v; done=1; next } { print }' .env > "$tmp"
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

abort_existing_volume() {
  log "ERROR: an existing database volume was found but .env has no database password."
  log "The DB password is bound to that volume; restore your previous .env (or the DB_PASSWORD"
  log "it contained). setup.sh will not generate a new one for an existing database."
  exit 1
}

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
log "Backend: UP | Login page via nginx: HTTP $code"
log "Open the PORTS tab and use the address for port 80 (SMMS App)."
