.PHONY: help build start stop restart logs migrate seed clean backup

# ── Config ─────────────────────────────────────────────
COMPOSE = docker compose
BACKEND = smms_backend
DB      = smms_postgres

help:
	@echo "SMMS – Available Commands"
	@echo "─────────────────────────────────────"
	@echo "  make build      Build all images"
	@echo "  make start      Start all services"
	@echo "  make stop       Stop all services"
	@echo "  make restart    Restart all services"
	@echo "  make logs       Tail all logs"
	@echo "  make migrate    Run DB migrations"
	@echo "  make seed       Seed initial data"
	@echo "  make backup     Backup PostgreSQL"
	@echo "  make clean      Remove containers + volumes"
	@echo "─────────────────────────────────────"

build:
	$(COMPOSE) build --no-cache

start:
	$(COMPOSE) up -d
	@echo "✓ SMMS running at http://localhost"

stop:
	$(COMPOSE) down

restart:
	$(COMPOSE) restart

logs:
	$(COMPOSE) logs -f --tail=100

logs-backend:
	$(COMPOSE) logs -f --tail=100 backend

migrate:
	docker exec -i $(DB) psql -U $${DB_USER} -d $${DB_NAME} \
	  < ./scripts/workflow_functions.sql
	@echo "✓ Migrations applied"

seed:
	docker exec -i $(DB) psql -U $${DB_USER} -d $${DB_NAME} \
	  -c "SELECT username FROM users WHERE role='system_admin';"
	@echo "✓ Seed data verified"

backup:
	docker exec $(DB) pg_dump -U $${DB_USER} $${DB_NAME} \
	  | gzip > ./backups/smms_$$(date +%Y%m%d_%H%M%S).sql.gz
	@echo "✓ Backup saved to ./backups/"

status:
	$(COMPOSE) ps

clean:
	$(COMPOSE) down -v --remove-orphans
	@echo "⚠ All containers and volumes removed"

# SSL self-signed (dev only)
ssl-dev:
	mkdir -p ./nginx/certs
	openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
	  -keyout ./nginx/certs/privkey.pem \
	  -out ./nginx/certs/fullchain.pem \
	  -subj "/C=SA/ST=Jazan/L=Jazan/O=SMMS/CN=localhost"
	@echo "✓ Dev SSL certs generated"
