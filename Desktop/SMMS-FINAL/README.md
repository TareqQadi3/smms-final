# SMMS – Smart Maintenance Management System

Production-grade maintenance management for Saudi hospitals.

---

## Quick Start

```bash
# 1. Setup
cp .env.example .env
nano .env

# 2. Build
make build

# 3. Start
make start

# 4. Verify
make status
curl http://localhost/api/auth/login -X POST
```

---

## Architecture

```
┌─────────────┐
│   Nginx     │ (Reverse proxy, SSL, SPA fallback)
└──────┬──────┘
       │
┌──────┴──────────────────┬────────────┐
│                         │            │
┌──────────────┐   ┌────────────┐  ┌──────┐
│   Frontend   │   │  Backend   │  │  WS  │
│   (React)    │   │ (SpringBoot)  │  │
└──────────────┘   └────────────┘  └──────┘
       │                  │
       └──────────┬───────┘
                  │
            ┌─────┴─────┐
            │           │
        ┌───────┐   ┌────────┐
        │   PG  │   │ Redis  │
        │  DB   │   │ Cache  │
        └───────┘   └────────┘
```

---

## API Endpoints

| Method | Endpoint | Auth | Role |
|--------|----------|------|------|
| POST | `/api/auth/login` | ❌ | - |
| GET | `/api/work-orders` | ✅ | any |
| POST | `/api/work-orders` | ✅ | control_room, resident, department |
| POST | `/api/work-orders/{id}/transition` | ✅ | by workflow |
| POST | `/api/work-orders/{id}/assign` | ✅ | control_room |
| POST | `/api/work-orders/{id}/warehouse` | ✅ | warehouse |
| POST | `/api/work-orders/{id}/approval` | ✅ | operating_company_manager |
| GET | `/api/notifications` | ✅ | any |
| GET | `/api/dashboard/...` | ✅ | role-specific |

---

## Workflow States

```
new → under_review → assigned → in_progress
                                    ├─→ completed → closed
                                    ├─→ waiting_materials → waiting_approval
                                    └─→ cancelled
```

Only valid transitions allowed per role. Enforced by `WorkflowEngine`.

---

## Database

**Tables:**
- regions, centers, control_rooms, users
- work_orders, ticket_history
- notifications, attachments
- user_control_room_access

**Triggers:**
- Auto ticket_number generation
- Auto SLA deadline calculation
- Auto control_rooms on new center
- Timestamp maintenance (created_at, updated_at)

**Constraints:**
- Soft delete (deleted_at)
- Unique control_room per center+type
- Foreign key integrity

---

## Deployment

### Development
```bash
make build
make start
make logs
```

### Production
```bash
# See DEPLOYMENT.md for full guide
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d

# Health check
./health-check.sh
```

### CI/CD
- GitHub Actions pipeline in `.github/workflows/ci-cd.yml`
- Auto build, test, security scan, deploy to staging

---

## Security

| Layer | Method |
|-------|--------|
| Auth | JWT (8h expiry) + refresh tokens (7d) |
| Roles | 11 distinct roles with granular permissions |
| API | Bearer token in Authorization header |
| DB | BCrypt password hashing, soft delete |
| Transport | HTTPS/TLS only in production |
| Locking | Optimistic ticket locks per user |

Default credentials:
```
username: sysadmin
password: Admin@1234
```
**Change immediately after first login.**

---

## Monitoring

```bash
# Health check
./health-check.sh

# Logs
docker compose logs -f backend
docker compose logs -f postgres

# Metrics
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/metrics

# Backup
make backup
```

---

## Support

- Docs: `./README.md`, `./DEPLOYMENT.md`
- Issues: Check logs with `make logs`
- Rollback: `git revert` + rebuild

---

## License

Private – SMMS © 2026
