#!/bin/bash
# One-command startup for GitHub Codespaces
# Run from anywhere: bash /workspaces/smms-final/Desktop/SMMS-FINAL/setup.sh

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== SMMS Codespace Setup ==="

# Ensure on the correct branch
BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")
if [ "$BRANCH" != "claude/smms-browser-test-setup" ]; then
  echo "Switching to claude/smms-browser-test-setup..."
  git fetch origin claude/smms-browser-test-setup
  git checkout claude/smms-browser-test-setup
  git pull origin claude/smms-browser-test-setup
fi

# Copy env file if not present
if [ ! -f .env ]; then
  echo "Creating .env from .env.codespaces..."
  cp .env.codespaces .env
fi

# Start all services
echo "Starting Docker services (first run downloads images and builds — ~5 minutes)..."
docker compose up -d --build

echo ""
echo "=== Services starting. Checking health in 90 seconds... ==="
sleep 90

docker compose ps
echo ""
echo "Testing backend health..."
docker exec smms_backend wget -qO- http://localhost:8080/actuator/health 2>/dev/null && echo " — UP" || echo " — still starting (may need 1 more minute)"
echo ""
echo "=== Setup complete ==="
echo "Go to the PORTS tab in VS Code, find port 80, and copy its forwarded URL."
