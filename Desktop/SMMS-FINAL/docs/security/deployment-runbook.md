# Codespace deployment runbook (branch `claude/pre-deploy-final`)

Status: **procedure only. It has never been executed in the owner's Codespace.** Rehearsal evidence from the sandbox is in `work-log.md` (Entries 10-12). No Claude agent has access to the Codespace; the owner (or a Claude session running inside the Codespace) runs every command and shares only outputs that contain no secrets. Anything marked READ-ONLY CHECK cannot be verified from the sandbox.

## 0. What protects the database (deployment guard)
`Desktop/SMMS-FINAL/setup.sh` refuses to build, migrate or rotate secrets when a database may already exist, unless one of these holds:
1. the working tree state equals the state recorded in `Desktop/SMMS-FINAL/.smms-deployed-commit` (HEAD commit **plus** a hash of uncommitted changes to tracked files; nothing changed since the last deployment by this script), or
2. `--backup-file <dump>` points to a dump created by `scripts/backup-db.sh` whose `<dump>.verified` file exists, is younger than 24 h and matches the dump's SHA-256, or
3. `SMMS_ALLOW_UNBACKED_DEPLOY=1` is set (explicit override for throwaway/dev databases; prints a warning).

"A database may exist" is detected fail-closed: any volume named `*postgres_data` (whatever the compose project is called), a container named `smms_postgres`, or a failing `docker volume ls` all count. The only cost of a false positive is being asked for a backup. Without git the state is `unknown` and never matches, so a backup is then always required. On a fresh machine without any database volume nothing changes.

The devcontainer runs `setup.sh --auto`. In `--auto` mode, when the state differs from the marker over an existing database, it does **not** deploy: it runs `docker compose start` (starts existing stopped containers; unlike `up` it never recreates them from a newer image or changed config, so it cannot migrate) and exits 0 with a message. Restarting the Codespace can therefore no longer migrate your database.

Limits (read this):
- A Codespace has this guard only after the branch has been pulled. **Until then its start runs the old `setup.sh` on whatever code is checked out.** A `git pull` itself deploys nothing: only a Codespace start/rebuild or a manual `setup.sh` runs the script.
- A Codespace keeps the `devcontainer.json` it was created or last rebuilt with. An old one still calls `setup.sh` **without** `--auto`; after the pull the new `setup.sh` treats that as a manual run and exits 1 without a verified backup (the stack's containers keep running as they were).
- The marker is untracked and local. Deleting it re-arms the guard (safe). Writing it by hand disables the check for that state; do not.
- New untracked files are not part of the state. Any new commit over an existing database needs a backup, even a docs-only commit (deliberate, cheap).
- `setup.sh` has no lock: do not run it twice at once (for example a manual run while the Codespace is starting).
- The guard cannot tell that a backup belongs to another database; `backup-db.sh` reads DB_USER/DB_NAME from the `.env` of the stack and compares row counts of every public table with the live database.
- Revert: remove the guard block and the `--auto` flag (one commit); nothing else depends on it.

## 1. Preconditions
- The owner chose the branch and a quiet window; users know they must sign in again (the leaked JWT secret is replaced, section 6).
- READ-ONLY CHECK: `git rev-parse --abbrev-ref HEAD` and `git rev-parse HEAD`, written down (rollback target). If HEAD is not a commit that exists on GitHub, stop.
- READ-ONLY CHECK: `docker volume ls --format '{{.Name}}' | grep -E 'postgres_data|uploads_data'` (names only) and `docker compose ps` (status only).

## 2. Pull (nothing is deployed by this step)
`git fetch origin && git switch <branch> && git pull --ff-only`; `git status` must be clean (`.env`, `.smms-deployed-commit` are untracked/ignored). This installs the guard and `scripts/backup-db.sh`. **Do not stop, restart or rebuild the Codespace between this step and step 4.** (If it restarts anyway, the guard refuses or only starts the existing containers; nothing is migrated.)

## 3. Backup (from `Desktop/SMMS-FINAL`)
```
bash scripts/backup-db.sh
```
All read-only on the live database. It: reads DB_USER/DB_NAME from `.env`; counts rows of every public table; `pg_dump -Fc` inside `smms_postgres` to `~/smms-backups/db-<UTC>.dump` (mode 600); checks the PGDMP header and `pg_restore -l`; **archives the uploads volume** (`uploads-<UTC>.tgz`, owned by you, mode 600; failure is fatal, `SMMS_SKIP_UPLOADS=1` only if there are no attachments to protect); starts a throwaway postgres of the same image (random password, no published port), restores the dump and compares the row count of every table (restored value must lie between the counts before and after the dump; non-numeric or missing tables fail); removes the throwaway container; only then writes `db-<UTC>.dump.verified` (SHA-256, commit, uploads hash, counts); copies `.env` (secrets: never commit or paste it).
It exits non-zero and writes no `.verified` file on any failure. Tested in the sandbox on throwaway databases only (16 tables, uploads archive, negative cases).

READ-ONLY CHECK (no secrets): the lines `ok <table>: restored=N` and `VERIFIED backup: <path>`.

Codespaces are not durable storage: download the `.dump`, `.verified`, `uploads-*.tgz` files to your own device before step 4. A backup that lives only inside the Codespace is lost with it.

## 4. Deploy (manual, with the verified backup)
```
bash setup.sh --backup-file ~/smms-backups/db-<UTC>.dump
```
It accepts the backup (warns if the uploads were skipped), replaces a known-leaked JWT secret in `.env` (every user must sign in again), runs `docker compose up -d --build` (volumes are kept), restarts nginx (so the new config applies) and waits for health; on success it records the state in `.smms-deployed-commit`.
Flyway applies V3..V12 on the existing database; each migration is one transaction. If one fails the backend never starts and no half-applied version is kept, but `restart: unless-stopped` makes the container crash-loop: run `docker compose stop backend` and see Rollback.

## 5. Verify (READ-ONLY CHECKS unless stated)
- `docker compose ps`: backend, postgres, redis, frontend, nginx running; health UP.
- `docker logs smms_backend 2>&1 | grep -E "Flyway|Successfully applied|Tenant integrity|SECURITY|ERROR"`: no ERROR; Flyway reaches version 12; a `SECURITY: ... default password` warning means an administrator still uses the public default (section "Default administrator accounts").
- Row counts not lower than in step 3.
- Browser: sign in as `admin`; if the banner says the password must be changed, change it (Settings → Security).
- As platform admin: `GET /api/admin/tenantless-users` and `GET /api/platform/diagnostics/summary` (ids and counts only); review. Nothing is changed automatically; fix tenants only after a fresh backup.
- Smoke: create a ticket, assign, start/complete, upload and download an attachment, sign out; the old token must now give 401.

## 6. Legacy tokens and refresh (transition plan)
- Because `setup.sh` replaces the leaked JWT secret, **every token issued before the deployment stops working immediately** (signature mismatch). That ends the "legacy tokens stay valid up to 8 h" window for this Codespace.
- The window remains only for an install whose JWT secret was already private and is not rotated: sid-less access tokens issued before the upgrade live until their own expiry (max 8 h); sid-less refresh tokens are rejected at once. For such an install either rotate `JWT_SECRET` during the deployment (forces re-login now) or accept up to 8 h. Do not add a code path that revives old tokens.
- The web frontend does not use refresh tokens (verified: no refresh call in `frontend/src`); web users sign in again when the 8 h access token expires.
- **Single-flight refresh is a requirement for any future client** (the technician app): refresh rotates the token and reuse of an old refresh token revokes the whole session with no grace window. A client that sends two refresh calls at once (two tabs, retry after a timeout) logs itself out. It must serialize refresh calls, persist the new refresh token before using it and treat 401 as "sign in again".

## 7. After
- Owner assigns tenants to tenant-less users via the admin endpoint and reviews the mismatch report; corrections are manual and follow a fresh `backup-db.sh`.
- Record date, commit and Flyway version in `work-log.md`.

## Rollback (and its limits)
Triggers (owner decides): health not UP within 5 minutes, Flyway error, login broken, counts lower than before. Load `DB_USER`/`DB_NAME` without printing them: `set -a; . ./.env; set +a`. `<UTC>` is the timestamp in the dump's file name.
1. `docker compose stop backend frontend`.
2. Keep the failed database for analysis, then restore (every command runs inside the postgres container):
   - `docker exec smms_postgres psql -U "$DB_USER" -d postgres -c "ALTER DATABASE \"$DB_NAME\" RENAME TO \"${DB_NAME}_failed_<UTC>\""`
   - `docker exec smms_postgres createdb -U "$DB_USER" "$DB_NAME"`
   - `docker exec -i smms_postgres pg_restore --no-owner --exit-on-error -U "$DB_USER" -d "$DB_NAME" < ~/smms-backups/db-<UTC>.dump`
   Then compare the row counts with the `.verified` file. (These exact commands, run on a throwaway database, restored the backed-up state and kept the failed copy.)
3. Attachments: if files were deleted or overwritten, restore them from `uploads-<UTC>.tgz` into the `uploads_data` volume. Normally new files only add and need no rollback.
4. `git switch --detach <recorded commit>`, then `docker compose up -d --build` for the previous code. If the previous checkout's `setup.sh` already contains the guard, add `SMMS_ALLOW_UNBACKED_DEPLOY=1` (the database is the one you just restored); older checkouts have no guard.
5. **Keep the new JWT secret** in `.env` (the old one is public; old code accepts any valid secret). Everyone signs in again.
6. Re-verify as in step 5. Drop `${DB_NAME}_failed_<UTC>` only after the owner has finished the analysis.
Explicit limits:
- **Everything written after the backup is lost on restore** (tickets, users, uploads created between step 3 and the rollback).
- The old code must **not** run against the new schema without restoring: pre-phase-3 code does not set child `tenant_id` and V6 makes it NOT NULL where possible.
- Never use `flyway repair` or delete rows from `flyway_schema_history` on the real database; restore instead.
- The rollback has never been exercised on the Codespace. The sandbox rehearsed backup, restore verification and the rollback SQL on throwaway databases, and the guard logic with a fake docker.

## Default administrator accounts (remediation plan, nothing automatic)
- The existing `admin` row and password are **not touched** by any step above. Login responses carry `passwordChangeRequired=true` while an account still uses the public default; the UI banner sends the user to Settings → Security. Since this branch the backend also logs a read-only `SECURITY` warning at startup (after all startup runners, so a just-created default admin is included) for every active system administrator that still matches the public default (user ids only). Nothing locks and nothing is written.
- Sequence that cannot lock you out: (1) sign in as `admin`; (2) change its password; (3) sign out and sign in with the new password to prove it works; (4) only then handle `sysadmin` (seeded by V1 with the same public default): reset its password from the admin UI or deactivate it. Never change both before one login is proven. Keep one working system administrator at all times.
- Fresh installs: set `SMMS_INITIAL_ADMIN_PASSWORD` (12-128 characters, only in the untracked `.env` or a Codespaces secret) before the first start to create `admin` with a private password; `setup.sh` deliberately does not generate it (the owner would not know the value). Without it the legacy default is used and flagged.
- Not implemented: blocking login until the password is changed (possible later, after the owner has done step 2).

## Logging of one-time tickets (nginx)
- The nginx access log (`smms_safe`) logs the request path only: the URI is cut at the first `?` or encoded `?` (`%3F`), so a ticket or token a client wrongly puts in a URL is not written.
- `/ws/` rejects any request containing `?` or `%3F` with 400 before proxying (also keeps tickets out of nginx's upstream-error log, which prints the full request line). The supported flow sends the ticket in the `Sec-WebSocket-Protocol` header, which is not logged.
- Residual: for other paths (e.g. `/api/...?x=...`) the nginx *error* log prints the query only while the upstream is down. The supported API never carries credentials in a query string; do not start doing so. In the `nginx:1.25-alpine` image both logs go to `docker logs smms_nginx` (not files).
- Tested: with a throwaway nginx (query, `%3F`, `%3f` probes: 400, no trace in the access log; `/ws/` probes leave nothing in the error log either) and on a real throwaway stack with Chromium (ticket flow still opens; query forms return 400; probe strings absent from nginx and backend logs). `setup.sh` restarts nginx after `up -d --build` because compose does not recreate it when only the mounted file changed.

## CI status (facts from GitHub, not claims)
- Before this phase only "Backend Tests" had ever run (7 runs, last on PR #2 for `85920af`).
- Workflows now also trigger on pushes to `claude/**` branches. A push to `claude/pre-deploy-final` produced real runs; see `work-log.md` Entry 12 for the recorded results. A push to such a branch that also has an open PR runs every workflow twice.
- `main`-only triggers are unchanged. Actions are referenced by tag, not pinned to a commit SHA (could not be verified offline).

## Feature program 1 additions (branch `claude/feature-program-1`)
- **Migrations:** Flyway V13-V14, V20-V25, V30-V34, V35-V39, V40-V44, V50-V51, V60 run on the first start after deploy. They add tables and backfills; they are not reversible by the app, so the verified backup (section 3) is the rollback path.
- **Config to review before enabling:** public request-status flag stays OFF in prod; notification providers (email/SMS) are unconfigured and report `not_configured`; PM scheduler and overdue/escalation jobs run inside the backend.
- **Single replica:** WebSocket tickets and schedulers are in-process; do not scale the backend beyond one replica until locking is added.
- **Known open issues:** PM `generate-now` creates a work order per call (see work-log Entry 13); NUL byte in an upload filename returns 500.
- **Not verified on the real deployment:** the Codespace, Safari/iPhone, real SMTP/SMS.
