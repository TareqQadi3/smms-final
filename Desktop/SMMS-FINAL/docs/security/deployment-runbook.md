# Codespace deployment runbook (branch `claude/security-phase-4`)

Status: **procedure only. It has never been executed in the owner's Codespace.** The sandbox rehearsal evidence is in `work-log.md` (Entry 10). No Claude agent has access to the Codespace; the owner runs every command and pastes back outputs that contain no secrets.

Important Codespace facts
- `.devcontainer/devcontainer.json` runs `bash Desktop/SMMS-FINAL/setup.sh` as `postStartCommand`: **starting or rebuilding the Codespace after the new code is pulled deploys and migrates the database immediately, with no backup.** Do not stop/restart the Codespace between step 1 and step 6, and take the backup (step 2) before pulling the branch.
- `restart: unless-stopped` makes a backend with a failed migration crash-loop (it never starts and Flyway keeps no half-applied version). Use `docker compose stop backend` while investigating.
- Old jar on the new schema without restoring the backup is **not supported** (V6 makes child `tenant_id` NOT NULL, old code does not set it).

## Default administrator password (backward compatible plan)
- Existing installs: the existing `admin` row and password are **not touched**. Login responses carry `passwordChangeRequired=true` while an account still uses the public default; the UI shows a banner and sends the user to Settings → Security. Nothing is locked.
- Fresh installs: set `SMMS_INITIAL_ADMIN_PASSWORD` (12-128 chars, only in the untracked `.env`/a Codespaces secret) before the first start to create `admin` with a private password. Without it the legacy default is used and flagged. `setup.sh` deliberately does not generate it (the owner would not know the value).
- The V1 seed account `sysadmin` also has the public default. After the owner has signed in with their own admin and changed its password, deactivate or change `sysadmin` through the UI. Do not automate this.
- Hard enforcement (blocking login until the password is changed) is **not implemented**; it is a possible later step after the owner has changed the password.

## Procedure
**Roles:**
- **Owner:** approves, runs every command in the Codespace terminal, keeps the backups and decides rollback.
- **Claude coordinator:** prepares the branch, the runbook and the sandbox rehearsal evidence, and reviews the outputs the owner pastes (no secrets pasted). No Claude agent accesses the Codespace.

**Preconditions:**
- the owner merged the reviewed branch into the branch the Codespace tracks;
- a quiet window;
- users told they must sign in again.

**Do not restart or rebuild the Codespace between steps 1 and 6.** `postStartCommand` runs `setup.sh` and would deploy unbacked-up.

1. **Record the current state** (cwd `Desktop/SMMS-FINAL`):
   - `git rev-parse --abbrev-ref HEAD; git rev-parse HEAD` → write it down;
   - `docker compose ps`;
   - `docker image ls | grep -i smms`;
   - load DB vars without printing: `set -a; . ./.env; set +a`;
   - `docker exec smms_postgres psql -U "$DB_USER" -d "$DB_NAME" -Atc "select to_regclass('flyway_schema_history')"`, and if present `… -c "select version,success from flyway_schema_history order by installed_rank"`;
   - row counts: `select (select count(*) from users),(select count(*) from work_orders),(select count(*) from attachments)`.
2. **Back up** (outside the repo):
   - `umask 077; mkdir -p ~/smms-backups; TS=$(date -u +%Y%m%dT%H%M%SZ)`;
   - `docker exec smms_postgres pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc > ~/smms-backups/db-$TS.dump`;
   - verify: `test -s` and `docker exec -i smms_postgres pg_restore -l < ~/smms-backups/db-$TS.dump | head`;
   - uploads: `docker run --rm -v <project>_uploads_data:/d:ro -v ~/smms-backups:/b alpine tar czf /b/uploads-$TS.tgz -C /d .` (volume name from `docker volume ls`);
   - `cp .env ~/smms-backups/env-$TS` (holds secrets: never commit or paste);
   - tag the current images: `docker tag <project>-backend:latest smms-backend:rollback-$TS` (same for the frontend).
3. **Restore rehearsal** (strongly recommended): restore the dump into a throwaway container with no published port (`docker run -d --name smms_restore_check -e POSTGRES_PASSWORD=<random> postgres:16-alpine`, then `pg_restore --no-owner`), compare the row counts, and remove the container. Optional (coordinator to provide `scripts/rehearse-upgrade.sh`): boot the new backend against that copy in project `smms-rehearsal`. Check that Flyway reaches V12 and read the reporter output, then `docker compose -p smms-rehearsal down -v`.
4. **Pull:** `git fetch origin && git switch <branch> && git pull --ff-only`. `git status` must be clean (`.env` is untracked).
5. **Deploy:** `bash setup.sh`. It rotates a leaked JWT secret if present, runs `compose up -d --build` (postgres/redis volumes kept) and waits for health.
6. **Verify:**
   - health UP;
   - `docker logs smms_backend 2>&1 | grep -E "Flyway|Successfully applied|Tenant integrity|Migrating"` with no ERROR;
   - history all `success = t`, max version 12;
   - row counts ≥ step 1;
   - in the browser: sign in as admin; change the password if `passwordChangeRequired`;
   - `GET /api/admin/tenantless-users` and `/api/platform/diagnostics/summary` as the platform admin → review;
   - smoke: create ticket → assign → technician start/complete → upload + download → logout, then reuse the old token → 401.
7. **After:**
   - the owner assigns tenants to tenant-less users;
   - reviews the mismatch report (manual fixes only after a fresh backup, with the runbook SQL template);
   - records the date, commit and Flyway version in the work log.

**Rollback** when health is not UP within 5 min, on a Flyway error, if login is broken or if counts dropped. Owner go is required:
1. `docker compose stop backend frontend`.
2. Keep the failed DB for forensics:
   - `docker exec smms_postgres psql -U "$DB_USER" -d postgres -c "ALTER DATABASE \"$DB_NAME\" RENAME TO \"${DB_NAME}_failed_$TS\""`;
   - `docker exec smms_postgres createdb -U "$DB_USER" "$DB_NAME"`;
   - `docker exec -i smms_postgres pg_restore --no-owner -U "$DB_USER" -d "$DB_NAME" < ~/smms-backups/db-$TS.dump`.
   - A partial run leaves V3..Vn committed: never "repair" by hand.
3. `git switch --detach <recorded commit>`.
4. `docker tag smms-backend:rollback-$TS <project>-backend:latest` (same for the frontend), then `docker compose up -d --no-build backend frontend`.
5. **Keep the new `.env` JWT secret** (do not restore the leaked one; old code accepts any valid secret). Uploads need no rollback (new code only adds files).
6. Re-verify as in step 6 with the old expectations.
7. Drop `${DB_NAME}_failed_$TS` only after the owner has finished the analysis.

Old jar on the new schema without restore is **not** supported: pre-M3 code does not set child `tenant_id` and V6 made it NOT NULL.
