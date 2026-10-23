---
name: smms-database-safety
description: Guards the SMMS PostgreSQL 16 schema, migrations, JPA enum mappings, transactions and backups. Use when touching entities, SQL scripts, DataInitializer, docker-compose DB settings, or when running any query or backup against the SMMS database.
---

# SMMS database safety

## When to use
Any change or review involving entities, enums, `scripts/*.sql`, `ddl-auto`, transactional service code, indexes, backup/restore, or ad-hoc SQL against the running database.

## Inputs / scope
Branch/diff, whether a live DB is reachable (container `smms_postgres`, db `smms_db`, user `smms_user` from `.env`; credentials are read by the container environment, never typed or printed).

## Relevant files (verified)
- `Desktop/SMMS-FINAL/scripts/init.sql` (base schema, native ENUM types, indexes ~L203-211, triggers `trg_set_sla`, `trg_ticket_number`, seed org data)
- `Desktop/SMMS-FINAL/scripts/V2__schema_migration.sql` (tenants, subscriptions, departments, assets, inventory, maintenance_types, `tenant_id` columns and indexes ~L194-197)
- `Desktop/SMMS-FINAL/scripts/workflow_functions.sql` (SLA breach UPDATE; applied only manually via `make migrate`)
- `Desktop/SMMS-FINAL/docker-compose.yml` (mounts only init.sql as `01_init.sql` and V2 as `02_v2_migration.sql` into `docker-entrypoint-initdb.d`; `workflow_functions.sql` is NOT mounted)
- `Desktop/SMMS-FINAL/Makefile` (`migrate`, `backup` targets; `clean` runs `down -v` and is FORBIDDEN)
- `Desktop/SMMS-FINAL/backend-source/src/main/resources/application-prod.yml` (`ddl-auto: validate`), `application-dev.yml` (`ddl-auto: update`, dev only)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/entity/` (`BaseEntity.java`, `WorkOrder.java`, `TicketHistory.java`, `User.java`, `InventoryItem.java`, `InventoryMovement.java`, ...)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/converter/TicketStatusType.java`, `TicketStatusConverter.java`
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/config/DataInitializer.java` (`migrateEnums`, `backfillTenantId`, `ensureDefaultSubscription`, `createAdminIfAbsent`)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/service/WorkOrderService.java` (class-level `@Transactional`)
- `Desktop/SMMS-FINAL/CLAUDE.md` section 2 (transaction rules)

## Procedure
1. **Init semantics.** `docker-entrypoint-initdb.d` scripts run ONLY when the data volume is empty. Editing `init.sql` or `V2__schema_migration.sql` does nothing for an existing database. Schema changes for a live DB need a new, reviewed, additive migration applied manually, and that needs user approval. Never "fix" this by dropping the volume.
2. **ddl-auto.** Prod must stay `validate`. Check `grep -rn "ddl-auto" Desktop/SMMS-FINAL/backend-source/src/main/resources Desktop/SMMS-FINAL/backend-source/application-prod.yml Desktop/SMMS-FINAL/docker-compose*.yml`. `update`/`create` anywhere reachable by compose is Critical. Compose sets `SPRING_PROFILES_ACTIVE: prod`.
3. **Enum mapping.** Every Java enum bound to a native PG ENUM must carry `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`; `TicketStatus` instead uses `@Type(TicketStatusType.class)` because its DB values are lowercase (`dbValue`). Check: `grep -rn "enum\|Enum" Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/entity | grep -v NAMED_ENUM`. Compare Java constants to `CREATE TYPE ... AS ENUM` in the SQL (`UserRole` vs `user_role`, `SubType` vs `sub_type`, `PlanType` / `TenantStatus` are UPPERCASE in V2). A mismatch fails at runtime (not at startup) under `validate`.
4. **DataInitializer.** Confirm each step is idempotent: `ALTER TYPE ... ADD VALUE IF NOT EXISTS`, backfill touches only `tenant_id IS NULL` rows (users exclude `system_admin`), admin created only if absent. Any new step must be safe to run on every boot and must never delete or overwrite data. Do not print the seeded admin credentials.
5. **Transaction rules (CLAUDE.md section 2).** Parts + status change must be one `@Transactional`; stock check before deduction (`quantity >= required`); closing date not before opening date. Evidence to record: `WorkOrderService` is class-level `@Transactional`; `InventoryItem`/`InventoryMovement` entities exist but no inventory repository, service or controller exists (`grep -rn "InventoryItem" Desktop/SMMS-FINAL/backend-source/src/main/java`), so stock check/deduction is NOT implemented; `close()` sets `closedAt = Instant.now()` and no CHECK constraint enforces `closed_at >= created_at` (`grep -n "CHECK" Desktop/SMMS-FINAL/scripts/*.sql`). Report these as gaps, not defects of an existing feature.
6. **Triggers vs Hibernate.** `ticket_number` and `sla_deadline` are set by DB triggers; Hibernate does not re-read them after insert (see QA-053 in `Desktop/SMMS-FINAL/docs/qa/test-plan.md`). Check any code that depends on them right after `save()`.
7. **Indexes.** Compare query predicates to indexes: `tenant_id` indexed on regions, centers, users, work_orders (V2) but check control_rooms, ticket_history, notifications, attachments, maintenance_types; `center_id` / `assigned_to_id` / `status` exist on work_orders. Propose additive `CREATE INDEX CONCURRENTLY` only as a recommendation, not an applied change.
8. **workflow_functions.sql.** Verify it is not mounted: `grep -n "workflow_functions" Desktop/SMMS-FINAL/docker-compose.yml` returns nothing (only `Makefile` references it). The SLA flag is actually set by `SlaScheduler` every 5 minutes.
9. **Backups.** Dump outside the repo, never commit: `docker exec smms_postgres pg_dump -U smms_user -d smms_db | gzip > ~/smms-backups/smms_$(date +%Y%m%d_%H%M%S).sql.gz` (create `~/smms-backups` first). `make backup` writes `./backups/` inside the project; do not use it unless `backups/` is git-ignored. Restore only into a NEW scratch database after user approval, never over `smms_db`.

Safe read-only queries:
```bash
docker exec smms_postgres psql -U smms_user -d smms_db -c "\dt"
docker exec smms_postgres psql -U smms_user -d smms_db -c "SELECT status, count(*) FROM work_orders GROUP BY 1;"
docker exec smms_postgres psql -U smms_user -d smms_db -c "SELECT count(*) FROM users WHERE tenant_id IS NULL;"
docker exec smms_postgres psql -U smms_user -d smms_db -c "\di+ idx_*"
```
Never select `password_hash` or token columns.

## Expected output
| ID | Severity | File:line | Evidence | Recommendation | Verified? |
|----|----------|-----------|----------|----------------|-----------|
| DB-001 | Medium | path:line | what code/query shows | additive, approved migration or code fix | verified by query / code-reading only |

Add a short migration plan (SQL, rollback, who applies it) for anything schema-related, marked "PROPOSED, needs approval".

## Verification
- `cd Desktop/SMMS-FINAL/backend-source && mvn -q test` (`TicketStatusTest`, `PlanConfigTest` cover enum/plan mapping).
- Schema vs entities: start the backend against a COPY or the existing DB with `ddl-auto: validate`; startup failure names the mismatch. Do not start/stop services unless the user asked.
- Row-level checks only via the read-only queries above; record the SQL and the result count.

## Safety restrictions
Read-only by default. FORBIDDEN without explicit written approval: `DROP`, `TRUNCATE`, `DELETE`/`UPDATE` without a reviewed WHERE, `ALTER` on live tables, `docker compose down -v`, `make clean`, deleting volumes, resetting or reseeding the DB. Never print or commit passwords, tokens, `.env` contents or dumps. Never make ports public (Postgres is bound to `127.0.0.1:5432`; keep it). No destructive or DoS testing. Never merge to main. Fixes only when bounded, justified and covered by a regression test; stop and ask before schema migrations or architectural changes. Distinguish PASS/FAIL/BLOCKED/NOT TESTED; never claim a feature exists without code evidence.
