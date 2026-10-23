# SMMS project memory (handoff for future AI sessions)

Last updated: 2026-10-23. Labels used: **Facts** (verified), **Assumptions**, **Open questions**. No secrets are stored here; never add any.

Related docs: [work-log.md](work-log.md) | [qa/test-plan.md](qa/test-plan.md) | [qa/test-results.md](qa/test-results.md) | [qa/evidence/README.md](qa/evidence/README.md) | [skills/README.md](skills/README.md) | [skills/audit.md](skills/audit.md) | [skills/import-report.md](skills/import-report.md) | skills/application-report.md (**not present in this checkout at time of writing; to be added by the coordinator**).

## 1. Purpose
SMMS is a multi-tenant maintenance/work-order management system, Arabic-first (RTL), with role-based access (admin, supervisors, technicians, residents, departments, warehouse, etc.). See `Desktop/SMMS-FINAL/CLAUDE.md` for the RBAC matrix and business rules (Arabic).

## 2. Architecture (Facts)
- Backend: Spring Boot 3.3, Java 21 (`backend-source`).
- Database: PostgreSQL 16 using **native ENUM types** (mapped in JPA with `@JdbcTypeCode(NAMED_ENUM)`) and **application-level tenant isolation** via `tenant_id` (not RLS).
- Cache/session helpers: Redis 7.
- Frontend: Next.js 14, Arabic-first RTL, Tailwind/shadcn (`frontend`).
- Reverse proxy: nginx. Orchestration: docker-compose with **5 services**. Postgres is bound to 127.0.0.1.
- Config: CORS via `CORS_ALLOWED_ORIGIN_PATTERNS`; `application-prod.yml` bundled; `.env.codespaces` holds TEST-only values (do not read or copy its contents into docs).

## 3. Repository and branch (Facts)
- Repo: https://github.com/TareqQadi3/smms-final
- Working branch: `claude/smms-browser-test-setup`. **Never merge to `main` without the user's explicit approval.**
- App root inside repo: `Desktop/SMMS-FINAL/`.

## 4. Key files (under `Desktop/SMMS-FINAL/`)
| Path | Role |
|---|---|
| `CLAUDE.md` | Project rules (Arabic), RBAC, workflow preference (section 6) |
| `docker-compose.yml`, `nginx/` | Services and proxy |
| `setup.sh` | One-command deployment used by the devcontainer |
| `scripts/init.sql`, `scripts/V2__schema_migration.sql`, `scripts/workflow_functions.sql` | DB schema, V2 migration, workflow functions (see DB-003) |
| `scripts/seed-test-accounts.sh` | Safe test-account seeder (dry-run default) |
| `backend-source/`, `frontend/` | Application code |
| `/home/user/smms-final/.devcontainer/devcontainer.json` | Codespaces auto-setup (repo root) |
| `/home/user/smms-final/.claude/skills/` | 111 repo-level skills |
| `docs/` | This documentation |

## 5. How to run (Facts)
Codespaces (the user's path): create a codespace on branch `claude/smms-browser-test-setup` -> `.devcontainer` auto-runs `Desktop/SMMS-FINAL/setup.sh` -> open the **PORTS** tab, port **80**, visibility **Private**.
Seeding test accounts (needs user authorization first): `bash Desktop/SMMS-FINAL/scripts/seed-test-accounts.sh` (dry run), then `... --apply` (prompts for admin and test password, hidden). Reset a password only with `--reset-password USER`.

## 6. Status (verified vs planned)
**Verified (Facts)**
- The user runs the app in a Codespace on this branch, logged in as admin and created Nasser (region supervisor, Jazan cluster) and Tareq (hospital supervisor, King Fahad hospital) in the UI.
- Fixes (below) are verified **on the Claude Code Remote cloud sandbox only** (ephemeral; not the Codespace, not the laptop): mvn 187 tests, 0 failures; frontend build OK; `tsc` has 4 pre-existing errors, unchanged.
- Sandbox API checks all PASS: scenario A end-to-end with lowercase values; upload 201; resident lock 403; refresh-as-bearer 403; lockout 429 after 5; reset body 204 / query 400; password absent from nginx logs; self role escalation blocked.
- Seeder verified on a throwaway sandbox DB: 15 created, 15 logins verified, idempotent re-run, explicit reset path HTTP 204.

**Not done / planned**
- The 15 test accounts are **not created in the user's Codespace** (seeder not run there).
- QA campaign **not started**; no QA row is PASS (see test-results.md).
- Workflow coverage per code: statuses `new, under_review, assigned, in_progress, waiting_materials, waiting_approval, completed, closed, cancelled`; scenarios A/D/G implemented; B/C/E/F/H partial; inventory/procurement/assets have tables but **no API**; emergency = priority `critical` (4h SLA trigger) only.

## 7. Design decisions and rationale
- App-level `tenant_id` isolation (chosen in prior sessions; rationale not recorded; RLS audit skill rejected as it targets Supabase).
- Native PG ENUMs with `NAMED_ENUM` mapping: required for Hibernate to write enum columns.
- Lowercase JSON status/subType values: the UI uses lowercase; `TicketStatus` now serializes lowercase with a param converter, `SubType` has a lenient creator (35a0edf).
- Generic `/transition` cannot jump to `assigned`, leave `waiting_approval` or reopen: those go through dedicated endpoints/roles.
- JWT `typ` claim: refresh tokens rejected as bearer (c46cafb). Brute-force lockout returns 429. Reset-password takes a JSON body so passwords do not land in nginx logs.
- Seeder is dry-run by default, no password literals, no silent resets, Codespaces guard.
- Multipart uploads skip the XSS body filter (it corrupted multipart).

## 8. DB, startup, testing, troubleshooting
- **DB:** schema comes from `scripts/init.sql` at first DB init; there is **no Flyway/Liquibase** (DB-002), so schema changes do not reach an existing database. Never reset/reseed the user's DB without authorization. See `skills/smms-database-safety`.
- **Startup:** `setup.sh` via devcontainer; if the page is unreachable check PORTS tab port 80 and `docker compose ps`.
- **Testing:** backend `mvn test` (187 tests at last run); QA plan in qa/test-plan.md; Playwright screenshots in qa/evidence.
- **Troubleshooting (Assumptions from prior commits):** enum insert errors -> check `@JdbcTypeCode(NAMED_ENUM)`; login blocked from browser -> check CORS origin pattern; Node build errors -> frontend images use Node 22.

## 9. Known bugs and risks (open)
- SEC-005 / DEF-005: FIXED (branch `claude/def-005-request-visibility`, 11cd966 + 26d1dbf) under provisional role rules; waiting for product-owner confirmation. Still open nearby: SEC-A users created by the platform admin get a null tenant until restart; SEC-B `GET /api/admin/users` is open to control_room; SEC-C and SEC-D accept any control-room grant, region or center; SEC-H no max page size; SEC-I tenant API returns raw entities; no department link in the data model (departments see only their own submissions).
- SEC-001: bulk import has no tenant handling.
- DB-001: TicketHistory/Attachment/Notification entities do not map `tenant_id`.
- DB-002: no migration tool. DB-003: `workflow_functions.sql` not mounted. DB-005: no CHECK constraints (e.g. closed_at >= created_at).
- SEC-011: a TEST JWT secret is committed in `.env.codespaces`; rotate for any real use.
- UI-002/UI-004 partially addressed (in progress by another agent); other open UI/defect items in test-results.md (DEF-007..012 not re-verified).
- Missing features: stock deduction, procurement/receipt, return-for-info, emergency escalation, duplicate detection; `notifyApprover` is empty.
- Disclosures (sandbox only): during the security audit a probe reset the sandbox `sysadmin` password and it was restored to the documented value; the sandbox nginx log contains probe passwords; leftover sandbox test data (QA-AUDIT/QA-VERIFY tickets, `zz.*` users). A sandbox spreadsheet (not committed) holds plaintext test passwords: keep local, rotate before real use. The earlier "17 vs 19 accounts" mismatch came from that spreadsheet describing the sandbox, not the Codespace.
- Audit findings files (SEC-001..014, DB-001..016, UI-001..015, workflow) are in the sandbox scratchpad and were **not committed**; summaries are in skills/audit.md and qa/test-results.md.

## 10. Milestones (commit hashes verified with `git log`)
| Commit | Summary |
|---|---|
| `1033405` | Initial commit |
| `f42f455`, `fb97a86` | Backend unit tests + CI; extended coverage |
| `8e279e8`, `bedf163`, `cdae583` | Docker blockers fixed; compose cleanup; Codespaces browser-testing prep |
| `f7f73e5` | NAMED_ENUM on PostgreSQL ENUM entity fields |
| `55bb342`, `fd9e9f7`, `5edee5b`, `3ef4fa3` | Node 22 lockfile; self-contained Codespace deploy; auto-start and login fix; universal devcontainer image |
| `c1a491f`, `fd1fdec` | Test-account seeder; made safe by default |
| `fd7ade9`, `9bae527` | QA plan drafts; QA docs, skills registry, audit |
| `481b8ed`, `2f952ba`, `626894b` | Project skills WIP; six smms-* skills finalized |
| `b17bae0`, `36d5386` | UI screenshots evidence; ignore agent worktrees |
| `35a0edf` | Status/subType binding, multipart XSS, lock RBAC, transition bypass, ticketNumber, Arabic labels |
| `c46cafb` | JWT typ, lockout, reset-password body, self-update scope, log/ignore hygiene |
| `b70ffed` | UI accessibility: aria-labels, html lang/dir, focus rings |
| `be5a2c8` | 105 spruvex-r skills imported |

## 11. Current stopping point and next action
Stopping point: audits done, fixes committed and pushed, docs written; UI work (UI-002/004) in progress by another agent; QA not started.
**Exact next action (user):** in the Codespace terminal run the seeder dry run (`bash Desktop/SMMS-FINAL/scripts/seed-test-accounts.sh`), review, then authorize `--apply`; afterwards authorize the QA campaign start. The Codespace must first pull the latest branch commits so the fixes are present (Assumption: it was created before them).

## 12. Open questions for the user
1. Confirm the provisional visibility rules applied by the DEF-005 fix (resident/department: own submissions; control_room: own center AND assigned control rooms; hospital supervisor and maintenance/electrical/operating-company managers: own center; warehouse: own center, materials tickets; region supervisor: own region; `other`: none).
2. Purpose of the `other` role.
3. Authorize a second test region/center/tenant for isolation tests?
4. Build the missing features or record them as gaps?
5. Authorize running the seeder in the Codespace?

## 13. Roadmap (Assumptions; proposed, not approved)
1. Seed accounts, start QA campaign (qa/test-plan.md).
2. Deploy the DEF-005 branch to the user's Codespace, then seed accounts only after explicit authorization and start the QA campaign.
3. Add a migration tool (Flyway) and map `tenant_id` on remaining entities; add CHECK constraints.
4. Rotate secrets; stop committing JWT secrets.
5. Implement missing features per user decision.

## 14. Workflow preference
Hard tasks: plan with Opus 5.5 only, execute with Sonnet 5.5 only, verify the model actually used; see `CLAUDE.md` section 6.


## Security phase 2 status (branch claude/security-phase-2)
- Fixed on branch: SEC-A, SEC-B, SEC-C, SEC-D, SEC-H, SEC-I, SEC-001.
- Still open: DB-001 (tenant_id on history/attachments/notifications), DB-002 (no migration tool), DB-005 (no CHECKs), department link, SEC-011 (committed test JWT secret — rotate before real use). Plans in docs/security/phase2-proposals.md.
- Roadmap gaps (not security): stock deduction, procurement, return-for-info, escalation, duplicate detection.

## Security phase 3 status (branch claude/security-phase-3, 2026-10-23)
- Done on branch: Flyway + V3-V8, tenant_id on child tables, safe startup, tenant assignment endpoint, secret removal + prod guard, user-admin authz parity, assign-400 fix, return-for-info, escalation notifications, duplicate warning. Details: work-log Entry 9, security/migrations-and-secrets.md.
- Still open: department link (users.department_id), wrong-tenant row detection, attachment download endpoint, refresh-token rotation/logout revocation, WebSocket browser auth, default admin password, further CHECKs, inventory/purchasing (roadmap.md), technician mobile app (mobile-technician-api.md).
- Deploying to the Codespace needs the owner's approval and a DB backup first; setup.sh will replace the leaked JWT secret and force re-login.

## Security phase 4 status (branch claude/security-phase-4, 2026-10-24)
- Done on branch: Flyway V1-V12, tenant diagnostics (read-only), session store with refresh rotation/logout revocation, WebSocket tickets, attachment download, upload idempotency, 401 entry point, default-admin protection, TypeScript errors fixed, frontend CI. Evidence: work-log Entry 10.
- Still open: department link (design only), wrong-tenant row correction (manual, owner decision), V1 `sysadmin` default password, one-replica limit for WS tickets, nginx query-string logging, action SHA pinning, inventory/purchasing, technician mobile app.
- Deploy only via security/deployment-runbook.md; the Codespace has never been touched by Claude.
