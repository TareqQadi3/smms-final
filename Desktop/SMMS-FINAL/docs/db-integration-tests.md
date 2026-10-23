# Database integration tests (opt-in)

Tests tagged `@Tag("db")` (classes named `*IT`, base class `com.smms.it.AbstractDbIT`) need a real
PostgreSQL. They are **skipped by default**: `mvn test` excludes the `db` group, so the normal build
needs no database. They only ever run against a **throwaway** database you name explicitly.

## Safety rules (enforced in code, `DbItSupport`)
- No default URL exists. Without `SMMS_IT_DB_URL` every DB test is skipped.
- The host must be `localhost`, `127.0.0.1` or `[::1]`.
- The database name must start with `smms_it` (for example `smms_it_fresh`). Anything else fails the run.
- Never point these tests at a project, development or production database: they create tenants,
  users and work orders, and the upgrade test creates and drops its own `smms_it_upg_*` database.

## Run them against a throwaway local PostgreSQL 16
```bash
PGD=$(mktemp -d /tmp/pg-it-XXXXXX); chown postgres "$PGD"; chmod 700 "$PGD"
PB=/usr/lib/postgresql/16/bin
runuser -u postgres -- $PB/initdb -D "$PGD/data" -A trust -U smms_it
runuser -u postgres -- $PB/pg_ctl -D "$PGD/data" -l "$PGD/log" -w start \
  -o "-p 55601 -c listen_addresses=127.0.0.1 -c unix_socket_directories= -c fsync=off"
$PB/psql -h 127.0.0.1 -p 55601 -U smms_it -d postgres -c "create database smms_it_fresh"

cd Desktop/SMMS-FINAL/backend-source
SMMS_IT_DB_URL=jdbc:postgresql://localhost:55601/smms_it_fresh \
SMMS_IT_DB_USER=smms_it \
mvn -Pit test                      # runs ONLY the db-tagged tests

# tear down: stop the server and delete only that directory
runuser -u postgres -- $PB/pg_ctl -D "$PGD/data" stop && rm -rf "$PGD"
```
Alternative: a throwaway container started under a unique name (for example a compose project
`smms-it-<random>` with a tmpfs data directory and no named volume) and removed with
`docker compose -p <name> down -v`. Never reuse a container or volume of the real stack.

Use `SMMS_IT_DB_PASSWORD` when the throwaway server requires a password. The Spring context starts
with `ddl-auto: validate`, so a mapped column missing from the schema fails the tests.

## What they cover today
- `MigrationUpgradeDbIT`: V1-V12 data of two tenants upgraded to V13/V14 (rows kept, neutral defaults,
  constraints enforce, second migrate is a no-op).
- `Fp1ContractsDbIT`: fresh V1-V14 migration + Hibernate validate (at context start), `@Version`
  lost-update protection, `WorkOrderService.createGenerated` with the DB triggers, idempotency
  service races (one execution, replays, 422 on a different fingerprint, rollback), 7-day cleanup.

New database tests: extend `AbstractDbIT`, name the class `*IT`, create your own rows (see
`DbFixtures`), and never rely on rows left by other tests.
