# Database migrations and secret rotation (implemented in security phase 3)

## Migration tool: Flyway
- Dependency `flyway-core` + `flyway-database-postgresql`; config in `application.yml` (`baseline-on-migrate: true`, `baseline-version: 2`, `validate-on-migrate`, `clean-disabled: true`).
- Existing databases (created by the docker entrypoint scripts) are baselined at version 2 and receive V3+. Empty databases run V1..V8. The compose entrypoint mounts stay for now.
- `db/migration/V1` and `V2` are byte copies of `scripts/init.sql` and `scripts/V2__schema_migration.sql`; `MigrationScriptsTest` enforces this. Any change to those scripts must be mirrored byte for byte or Flyway checksum validation fails on migrated databases.
- Dev profile now uses `ddl-auto: validate` (was `update`).

| Version | Purpose |
|---|---|
| V3 | `user_role` value `other` (was DataInitializer); refuses pre-V2 databases |
| V4 | tenant_id derived from verified parents only (center<-region, room/user/work order/etc.<-center); system_admin cleared |
| V5 | tenant_id for history/attachments (from work order) and notifications (user or work order; conflicts left untouched and reported) |
| V6 | indexes, FKs, safe CHECKs; NOT NULL only when 0 NULL rows and 0 tenant-less work orders, otherwise deferred with a warning |
| V7 | default tenant (only if absent) and tenant for the three fixed `init.sql` seed ids, only where NULL |
| V8 | unique index on `lower(username)`; skipped with a warning if duplicates exist |

Ambiguous rows are never defaulted: they stay NULL and are reported (migration NOTICEs and `TenantIntegrityReporter` at startup). An admin fixes tenant-less users via `GET /api/admin/tenantless-users` and `POST /api/admin/users/{id}/tenant`. Follow-up F-A1: fix tenant-less work orders and their children manually, then re-run V6's guarded statements (idempotent) to apply the deferred NOT NULL/CHECK.

### Recovery
1. Before touching any real database: `pg_dump -Fc` backup (needs the owner's authorization for the Codespace).
2. Each migration is one transaction; a failure rolls back and the app does not start. Fix the script while unapplied; `flyway repair` if a failed row was recorded.
3. Logical rollback (manual, authorized only): drop V6 constraints/indexes, `DROP NOT NULL` on history/attachments (before redeploying an older jar, which does not set tenant_id), delete `flyway_schema_history` rows >= 3. V4/V5 data changes only filled NULLs or corrected rows to their parent's tenant and are not undone; record before/after counts.

## Secrets
- The previous test `JWT_SECRET`, `DB_PASSWORD` and `REDIS_PASSWORD` were committed in `.env.codespaces` (public git history): treat them as permanently compromised. History is not rewritten; `.gitleaksignore` holds fingerprints only.
- `.env.codespaces` and `.env.example` now contain placeholders. `setup.sh` generates random secrets into the untracked `.env` (mode 600, never printed). On an existing install it replaces only a JWT line that matches a known public value; DB and Redis passwords are not touched. If `.env` is missing but a database volume exists, it aborts instead of generating mismatching credentials.
- Under the `prod` profile the backend refuses to start with a secret shorter than 32 bytes, a placeholder, or a known-public secret (SHA-256 fingerprint comparison; the value is never logged).
- Rotation (no values here): (1) existing install: pull, run `setup.sh`, all users sign in again; (2) manual: put the output of `openssl rand -base64 48 | tr -d '\n'` into `JWT_SECRET` in the untracked `.env` or a Codespaces secret, recreate the backend, record the date; (3) DB: `ALTER USER` then update `DB_PASSWORD`, recreate the backend; Redis: change `REDIS_PASSWORD`, recreate redis and backend.
- Not changed: the default admin password (`Admin@1234`, documented for the Codespace login; to be rotated by the owner), the literal dev secret in `application-dev.yml` (only its hash is blocked in prod).
- `.github/workflows/secret-scan.yml` and `.gitleaks.toml` scan pull requests (not yet run in real GitHub Actions).
