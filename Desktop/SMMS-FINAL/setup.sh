#!/bin/bash
# Starts the full SMMS stack. Safe to re-run: keeps existing data volumes.
# Runs automatically in GitHub Codespaces (see /.devcontainer/devcontainer.json).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

log() { echo "[smms-setup] $*"; }

log "Waiting for Docker..."
for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && break; sleep 2; done
docker info >/dev/null 2>&1 || { log "ERROR: Docker is not available"; exit 1; }

if [ ! -f .env ]; then
  log "Creating .env from .env.codespaces (temporary test credentials)"
  cp .env.codespaces .env
fi

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
