# Codespace deployment runbook (branch `claude/pre-deploy-safety`, supersedes the phase-4 draft)

Status: **procedure only. It has never been executed in the owner's Codespace.** Rehearsal evidence from the sandbox is in `work-log.md` (Entries 10 and 11). No Claude agent has access to the Codespace; the owner runs every command and pastes back only outputs that contain no secrets. Anything marked READ-ONLY CHECK cannot be verified from the sandbox.

## 0. What protects the database now (deployment guard)
`Desktop/SMMS-FINAL/setup.sh` refuses to build, migrate or rotate secrets over an **existing database volume** unless one of these holds:
1. the commit that is checked out equals the commit recorded in `Desktop/SMMS-FINAL/.smms-deployed-commit` (nothing changed since the last deployment by this script), or
2. `--backup-file <dump>` points to a dump created by `scripts/backup-db.sh` whose `<dump>.verified` file exists, is younger than 24 h and matches the dump's SHA-256, or
3. `SMMS_ALLOW_UNBACKED_DEPLOY=1` is set (explicit override for throwaway/dev databases; prints a warning).

On a fresh machine with no database volume nothing changes: `setup.sh` behaves as before.

The devcontainer now runs `setup.sh --auto`. In `--auto` mode, when code changed over an existing database, it **does not deploy**: it starts the already-built containers with `docker compose up -d --no-build` and exits 0 with a message. Restarting the Codespace therefore can no longer migrate your database.

Limits of the guard (read this):
- A Codespace keeps the `devcontainer.json` it was created or last rebuilt with. An old Codespace still runs `setup.sh` **without** `--auto` on start. The guard still protects it, because the new `setup.sh` (once pulled) treats that call as manual and exits 1 without a verified backup. But **a Codespace that has not pulled this branch has no guard at all**: its start runs the old `setup.sh` on whatever code is checked out.
- Therefore: take the backup (step 2) **before** pulling anything and do not stop or rebuild the Codespace before step 5.
- The marker file is untracked and local. Deleting it re-arms the guard (safe direction). Writing it by hand disables the check for that commit; do not.
- The guard compares commits, not migrations: any new commit over an existing volume needs a backup, even a docs-only commit. This is deliberate and cheap.
- It cannot detect a wrong backup of a different database. `backup-db.sh` reads DB_USER/DB_NAME from the `.env` of the stack being deployed and compares row counts with the live database.
- Revert: remove the guard block and `--auto` flag (one commit); nothing else depends on it.

## 1. Preconditions
- The owner merged or checked out the reviewed branch and chose a quiet window.
- Users know they must sign in again (the leaked JWT secret is replaced; see section 6).
- READ-ONLY CHECK: `git rev-parse --abbrev-ref HEAD` and `git rev-parse HEAD` in the Codespace, written down (rollback target). If HEAD is not a commit that exists on GitHub, stop.

## 2. Backup (before pulling, from `Desktop/SMMS-FINAL`)
```
bash scripts/backup-db.sh
```
What it does (all read-only on the live database): counts rows; `pg_dump -Fc` inside `smms_postgres` to `~/smms-backups/db-<UTC>.dump` (mode 600); checks the PGDMP header and `pg_restore -l`; starts a throwaway postgres of the same image (random password, no published port), restores the dump into it and compares row counts for tenants, users, work_orders, ticket_history, attachments, notifications, centers, regions and control_rooms (restored value must lie between the counts before and after the dump); removes the throwaway container; writes `db-<UTC>.dump.verified` (SHA-256, commit, counts); copies `.env` (holds secrets; never commit or paste it) and archives the `uploads_data` volume to `uploads-<UTC>.tgz` (needs the `alpine` image; if the archive fails the script warns: attachments are then NOT backed up and you must not deploy until they are).
It exits non-zero and writes no `.verified` file if any check fails. Tested in the sandbox on a throwaway database only.

READ-ONLY CHECK (copy this output back, it has no secrets): the lines `[smms-backup] ok <table>: restored=N` and `VERIFIED backup: <path>`.

Keep the backups outside the repository (the default `~/smms-backups` is). Codespaces are not durable storage: also download the `.dump`, `.verified` and `uploads-*.tgz` files to your own device before step 4. A backup that lives only inside the Codespace is lost with it.

## 3. Pull
`git fetch origin && git switch <branch> && git pull --ff-only`; `git status` must be clean (`.env`, `.smms-deployed-commit` are untracked/ignored).

## 4. Deploy (manual, with the verified backup)
```
bash setup.sh --backup-file ~/smms-backups/db-<UTC>.dump
```
It accepts the backup, replaces a known-leaked JWT secret in `.env` (every user must sign in again), runs `docker compose up -d --build` (volumes are kept) and waits for health. On success it records the commit in `.smms-deployed-commit`.
Flyway applies V3..V12 on the existing database inside single-transaction migrations. If a migration fails the backend never starts and keeps no half-applied version, but `restart: unless-stopped` makes the container crash-loop: run `docker compose stop backend` and go to Rollback.

## 5. Verify (READ-ONLY CHECKS unless stated)
- `docker compose ps`: backend, postgres, redis, frontend, nginx running; health UP.
- `docker logs smms_backend 2>&1 | grep -E "Flyway|Successfully applied|Tenant integrity|ERROR"`: no ERROR; Flyway reaches version 12.
- Row counts not lower than the counts printed in step 2.
- Browser: sign in as `admin`; if the banner says the password must be changed, change it (Settings → Security).
- As platform admin: `GET /api/admin/tenantless-users` and `GET /api/platform/diagnostics/summary` (responses contain ids and counts only); review. Nothing is changed automatically; fix tenants only after a fresh backup.
- Smoke: create a ticket, assign, start/complete, upload and download an attachment, sign out; the old token must now give 401.

## 6. Legacy tokens and refresh (transition plan)
- Because `setup.sh` replaces the leaked JWT secret, **every token issued before the deployment stops working immediately** (signature mismatch). That ends the "legacy tokens stay valid up to 8 h" window for this Codespace.
- That window remains only for an install whose JWT secret was already private and is not rotated: sid-less access tokens issued before the upgrade live until their own expiry (max 8 h); sid-less refresh tokens are rejected at once. For such an install either rotate `JWT_SECRET` during the deployment (forces re-login now) or accept up to 8 h. Do not add a code path that revives old tokens.
- The web frontend does not use refresh tokens at all (verified: no refresh call in `frontend/src`), so web users simply sign in again when the 8 h access token expires.
- **Single-flight refresh is a requirement for any future client** (the technician app): refresh rotates the token and any reuse of an old refresh token revokes the whole session with no grace window. A client that sends two refresh calls at once (two tabs, retry after timeout) logs itself out. The client must serialize refresh calls, persist the new refresh token before using it and treat 401 as "sign in again".

## 7. After
- Owner assigns tenants to tenant-less users via the admin endpoint, and reviews the mismatch report; corrections are manual and follow a fresh `backup-db.sh`.
- Record date, commit and Flyway version in `work-log.md`.

## Rollback (and its limits)
Triggers (owner decides): health not UP within 5 minutes, Flyway error, login broken, counts lower than before.
1. `docker compose stop backend frontend`.
2. Keep the failed database for analysis: `docker exec smms_postgres psql -U "$DB_USER" -d postgres -c "ALTER DATABASE \"$DB_NAME\" RENAME TO \"${DB_NAME}_failed_<UTC>\""` (needs no other connections: the backend is stopped), then `createdb`, then `pg_restore --no-owner -U "$DB_USER" -d "$DB_NAME" < ~/smms-backups/db-<UTC>.dump`. Load `DB_USER`/`DB_NAME` with `set -a; . ./.env; set +a` (do not print them).
3. `git switch --detach <recorded commit>` and rebuild or retag the previous images (`docker compose up -d --build`; the guard needs `SMMS_ALLOW_UNBACKED_DEPLOY=1` for this step because the restored database is the backed-up one).
Explicit limits:
- **Everything written after the backup is lost on restore** (tickets, uploads, users created between step 2 and the rollback). Uploads are not rolled back: new files stay on the volume, harmlessly.
- The old code must **not** run against the new schema without restoring: pre-phase-3 code does not set child `tenant_id` and V6 makes it NOT NULL where possible.
- Sessions: tokens issued by the new version are invalid after rollback to the old secret only if you restore the old `.env`; **keep the new JWT secret** (the old one is public) and accept that everyone signs in again.
- Never use `flyway repair` or delete rows from `flyway_schema_history` by hand on the real database; restore from backup instead.
- The rollback has never been exercised on the Codespace; the sandbox rehearsal covered backup, verified restore into a throwaway database and the guard logic only.

## Default administrator accounts (remediation plan, nothing automatic)
- Existing `admin` row and password are **not touched** by any step above. Login responses carry `passwordChangeRequired=true` while an account still uses the public default; the UI banner sends the user to Settings → Security. Nothing locks.
- Sequence that cannot lock you out: (1) sign in as `admin`; (2) change its password; (3) sign out and sign in again with the new password to prove it works; (4) only then handle `sysadmin` (seeded by V1 with the same public default): reset its password from the admin UI or deactivate it. Never change both before one login is proven. Keep one working system administrator at all times.
- Fresh installs: set `SMMS_INITIAL_ADMIN_PASSWORD` (12-128 characters, only in the untracked `.env` or a Codespaces secret) before the first start to create `admin` with a private password; `setup.sh` deliberately does not generate it. Without it the legacy default is used and flagged.
- Possible later hardening (not implemented): a read-only startup warning when any active account still matches the public default, and optional blocking of login until the password is changed once the owner has done step 2.

## Logging of one-time tickets (nginx)
- The nginx access log now uses `smms_safe` (`$uri` without the query string), so tokens or tickets a client wrongly puts in a URL are never written to it.
- `/ws/` rejects any request that has a query string with 400 before proxying, which also keeps tickets out of nginx's upstream-error log (that log prints the full request line). The supported flow sends the ticket in the `Sec-WebSocket-Protocol` header, which is not logged.
- Residual: for other paths (e.g. `/api/...?x=...`) the nginx *error* log prints the query only when the upstream is down. The supported API never carries credentials in a query string; do not start doing so.
- Tested in the sandbox with a throwaway nginx: access log shows no query; `/ws/...?ticket=` returns 400 and leaves no trace; a header-only request is proxied. Not tested through the real stack with a browser after this change.
- nginx reads its bind-mounted config only at start, so `setup.sh` now runs `docker compose restart nginx` after `up -d --build`; the new log format and the `/ws/` rule take effect with the deployment.

## CI status (facts from GitHub, not claims)
- GitHub Actions run history for this repository (queried through the GitHub API): 7 runs, all workflow "Backend Tests", all successful, the latest on pull request #2 for commit `85920af`. No run exists for commits after `85920af`, and the `Secret Scan` and `Frontend CI` workflows have **never run** (they are only on the phase-3/4 branches, and trigger on pull requests and pushes to main). Their YAML was validated with actionlint 1.7.7 in the sandbox only.
- Opening a pull request from this branch would trigger Backend Tests, Secret Scan and Frontend CI; that was not done because it was not requested.
