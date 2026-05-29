#!/bin/bash
# ══════════════════════════════════════════════════════
#  SMMS Health Check Script
# ══════════════════════════════════════════════════════

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "🏥 SMMS Health Check"
echo "─────────────────────────────────────"

# ── Services ──────────────────────────────────────────
check_service() {
  if docker compose ps "$1" | grep -q "Up"; then
    echo -e "${GREEN}✓${NC} $1 is running"
    return 0
  else
    echo -e "${RED}✗${NC} $1 is DOWN"
    return 1
  fi
}

echo "Checking services..."
check_service "postgres"   || exit 1
check_service "redis"      || exit 1
check_service "backend"    || exit 1
check_service "frontend"   || exit 1

# ── Database ──────────────────────────────────────────
echo -e "\nDatabase connectivity..."
if docker compose exec -T postgres pg_isready -U ${DB_USER} &>/dev/null; then
  echo -e "${GREEN}✓${NC} PostgreSQL responding"
else
  echo -e "${RED}✗${NC} PostgreSQL not responding"
  exit 1
fi

# Count tables
TABLE_COUNT=$(docker compose exec -T postgres psql -U ${DB_USER} -d ${DB_NAME} -c \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';" \
  2>/dev/null | tail -1 | xargs)
echo "  Tables: $TABLE_COUNT"

# ── Redis ────────────────────────────────────────────
echo -e "\nCache connectivity..."
if docker compose exec -T redis redis-cli -a ${REDIS_PASSWORD} ping &>/dev/null; then
  echo -e "${GREEN}✓${NC} Redis responding"
else
  echo -e "${RED}✗${NC} Redis not responding"
  exit 1
fi

# ── Backend API ───────────────────────────────────────
echo -e "\nBackend API..."
API_HEALTH=$(curl -s http://localhost:8080/actuator/health || echo '{"status":"DOWN"}')
if echo "$API_HEALTH" | grep -q '"status":"UP"'; then
  echo -e "${GREEN}✓${NC} API /actuator/health UP"
else
  echo -e "${YELLOW}⚠${NC}  API health: $API_HEALTH"
fi

# ── Frontend ──────────────────────────────────────────
echo -e "\nFrontend..."
if curl -s https://localhost/health -k | grep -q "OK"; then
  echo -e "${GREEN}✓${NC} Frontend responding"
else
  echo -e "${YELLOW}⚠${NC}  Frontend health check inconclusive"
fi

# ── Disk Usage ────────────────────────────────────────
echo -e "\nSystem resources..."
DISK_USAGE=$(docker system df | grep -A1 "Images" | tail -1 | awk '{print $5}')
echo "  Docker disk usage: $DISK_USAGE"

MEM_USAGE=$(docker compose stats --no-stream | tail -1 | awk '{print $7}')
echo "  Memory usage: $MEM_USAGE"

# ── Backup Status ─────────────────────────────────────
echo -e "\nBackups..."
if [ -d "backups" ]; then
  LATEST_BACKUP=$(ls -t backups/db_*.sql.gz 2>/dev/null | head -1)
  if [ -n "$LATEST_BACKUP" ]; then
    MOD_TIME=$(stat -f%m "$LATEST_BACKUP" 2>/dev/null || stat -c%Y "$LATEST_BACKUP")
    CURRENT_TIME=$(date +%s)
    HOURS_AGO=$(( ($CURRENT_TIME - $MOD_TIME) / 3600 ))
    if [ $HOURS_AGO -lt 24 ]; then
      echo -e "${GREEN}✓${NC} Latest backup: ${HOURS_AGO}h ago"
    else
      echo -e "${YELLOW}⚠${NC}  Latest backup: ${HOURS_AGO}h ago (over 24h)"
    fi
  fi
fi

echo -e "\n─────────────────────────────────────"
echo -e "${GREEN}✓ All checks passed${NC}"
