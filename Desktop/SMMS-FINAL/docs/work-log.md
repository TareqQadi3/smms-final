# SMMS work log

Chronological. Facts only; secrets never recorded. See [project-memory.md](project-memory.md) for the overall picture.

## Prior sessions (summary)
- Windows-laptop work produced the codebase (backend-source, frontend, scripts).
- Earlier on branch `claude/smms-browser-test-setup`: V2 schema migration (`scripts/V2__schema_migration.sql`), `application-prod.yml` bundled, `.env.codespaces` (TEST-only values), Codespaces devcontainer/`setup.sh`, configurable CORS (`CORS_ALLOWED_ORIGIN_PATTERNS`), NAMED_ENUM fixes (TenantSubscription, Asset, InventoryMovement), Node 22 frontend images, Postgres bound to 127.0.0.1.
- The user created a Codespace on the branch and confirmed admin login plus creating Nasser and Tareq in the UI.

## 2026-10-23 (this session; Claude Code Remote cloud sandbox, ephemeral)

### Entry 1 - Safe test-account seeder
- Task: seeder for 15 test accounts. Changes: dry-run default, `--apply`, no silent password resets (`--reset-password USER` only), no password literals, CODESPACES guard, prerequisite checks; reset via JSON body.
- Files: `scripts/seed-test-accounts.sh`. Commits: `fd1fdec` (and `c46cafb` for JSON reset).
- Run: on a throwaway sandbox DB. Result: 15 created, 15 logins verified, idempotent re-run OK, explicit reset HTTP 204.
- Decision: do not run in the user's Codespace without authorization. Next: user authorizes.
- Note: the earlier 17 vs 19 account count came from a sandbox spreadsheet (not committed) that described the sandbox state.

### Entry 2 - Skills installation and review
- Installed (user-level, sandbox only): Arena (default 16 agents), brag 0.4.0, HyperFrames 0.8.187; 10 skills from jakubkrehel/skills (8 UI) and secureIO (threat-model, security-arch-review).
- Tests: HyperFrames render PASS via `npx hyperframes@0.8.186`; bundled launcher pins unpublished 0.8.187; GSAP CDN blocked by sandbox proxy, so vendor locally.
- Rejected: rls-audit (no license, Supabase-specific), nicohodt ui-ux (overlap, sends data to Gemini, loads .env). Deferred: security-scan, security-audit.
- Files: `docs/skills/README.md`. Commits: `9bae527`.

### Entry 3 - Project skills and import
- Wrote 6 smms-* skills (`626894b`; WIP in `481b8ed`, `2f952ba`). Imported 105 spruvex-r skills (`be5a2c8`), humanizer excluded (empty), 0 conflicts, 452 existing files verified unchanged by sha256.
- Files: `.claude/skills/*`, `docs/skills/import-report.md`.

### Entry 4 - QA plan and audits
- Built `docs/qa` plan from real code (`fd7ade9`, `9bae527`); screenshots added in `b17bae0`.
- Four read-only audits (Sonnet, using smms-* skills): security/tenant SEC-001..014, database DB-001..016, workflow/tests, UI-001..015. Findings files stayed in the sandbox scratchpad (not committed).
- Side effects (sandbox only): a probe reset the sandbox `sysadmin` password, restored to the documented value; sandbox nginx log contains probe passwords; leftover test data (QA-AUDIT/QA-VERIFY tickets, `zz.*` users).

### Entry 5 - Fixes with regression tests
- `35a0edf`: TicketStatus lowercase JSON + param converter; SubType lenient creator and new-request mapping; XSS filter skips multipart; lock RBAC; generic `/transition` cannot jump to assigned / leave waiting_approval / reopen; create refresh gives ticketNumber; Arabic status/priority labels. Reason: DEF-001/002/003/006 and related.
- `c46cafb`: JWT `typ` (refresh not accepted as bearer); lockout wired to 429; reset-password JSON body; self-update cannot change role/scope; admin password not logged; backups ignored. Reason: DEF-004, SEC-002/003/004/006/008/009.
- `b70ffed`: aria-labels, html lang/dir follows language, login focus rings (UI findings).
- Tests actually run: `mvn` 187 tests, 0 failures; frontend build OK; `tsc` 4 pre-existing errors unchanged.
- Sandbox API verification, all PASS: scenario A with lowercase values; upload 201; resident lock 403; refresh-as-bearer 403; lockout 429 after 5; reset body 204 / query 400; password absent from nginx logs; self role escalation blocked.
- Not verified in the user's Codespace.

### Entry 6 - Documentation (this task, Sonnet 5.5)
- Wrote `docs/project-memory.md`, `docs/work-log.md`; appended to `docs/skills/README.md` (complete inventory), `CLAUDE.md` (section 6), `docs/qa/test-plan.md` and `docs/qa/test-results.md` (post-fix status). No code changed; nothing committed by this agent.
- Commands: `ls` and `git log` to verify skill counts (12 user-level folders, 111 repo-level of which 6 smms-*) and commit hashes.
- Another agent was concurrently working on UI-002/004 in this checkout.
- Next steps: user authorizes seeding (dry run then `--apply` in the Codespace), then QA campaign; answer open questions in project-memory.md section 12.

### Entry 7 - DEF-005 request visibility fix (2026-10-23, Opus plan / Sonnet implementation)
- Phase: the plan was written by an agent that reported model `claude-opus-5-5`; implementation and tests by agents that reported `claude-sonnet-5-5`. The session model itself was Sonnet 5.5, so Opus was used only through the planning agent.
- Environment: Claude Remote sandbox (isolated git worktree, branch `claude/def-005-request-visibility`, based on 628dbfe). Not the user's Codespace.
- Changes: new `WorkOrderAccessPolicy` + `WorkOrderScope` used by search, detail, history, attachments, every write path, notifications, dashboards and the WebSocket; `/uploads` returns 404 in nginx (attachments are served only through the access-controlled API). Out-of-scope tickets return 404. Commits `11cd966`, `26d1dbf`.
- Tests actually run: `mvn test` 258 tests, 0 failures (baseline 187). Isolated two-tenant throwaway stack (own compose project, own ports, temporary storage): 222/223 probes passed; the 1 miss was a wrong probe expectation (technician filter forced to self, nothing widened). Cross-tenant, cross-center, cross-region and same-center isolation were tested there.
- Not done / still open: SEC-A, SEC-B, SEC-C, SEC-D, SEC-H, SEC-I (see project-memory.md); not deployed to the user's Codespace; seeding and QA campaign not started; frontend unchanged.
- Next step: user pulls the branch into the Codespace (see final report), then authorizes seeding.

## Entry 8 — Security phase 2 (branch `claude/security-phase-2`, base `85920af`)
- Planned by claude-opus-5-5, implemented by claude-sonnet-5-5 (both self-reported by the agents).
- Fixed: SEC-A (new records get tenant immediately; tenant-less non-admin users refused at login/JWT), SEC-B (user list scoped; new `assignable-technicians` endpoint replaces the picker's use of the user list), SEC-C/D (`AdminScopePolicy`: rank + center/region/room scope on users, centers, rooms, regions), SEC-001 (bulk import tenant-scoped, validated, all-or-nothing), SEC-H (`PageRequests`: size<=100, page>=0, sort whitelist), SEC-I (Tenant DTOs).
- Verified: 483 backend unit tests, 0 failures (baseline 258); throwaway single-Postgres probe in the sandbox. NOT verified in the user's Codespace; NOT browser-tested.
- Not implemented (proposals only): `security/phase2-proposals.md` (tenant_id on history/attachments/notifications, department link, Flyway + CHECKs, JWT rotation).
- Deploy note: users created earlier with a NULL tenant cannot log in until the app restarts once (DataInitializer derives the tenant from their center/region/room, or the single tenant). Users it cannot derive stay locked out by design.

## Entry 9 — Security phase 3 (2026-10-23, branch `claude/security-phase-3`, base `d6fcf11`)
Models: plan by claude-opus-5-5; workstreams A-D and the integration verifier by claude-sonnet-5-5 (identities self-reported by each agent; not independently verifiable). Four agents ran in separate git worktrees with disjoint file ownership; merged A, B, C, D with no conflicts.
- **A — migrations:** Flyway (baseline 2), V3-V6, tenant_id mapped on ticket_history/attachments/notifications (set in `@PrePersist`). Coordinator added V7 (default tenant + fixed seed ids, only where NULL) and V8 (unique `lower(username)`).
- **B — startup/secrets:** DataInitializer no longer rewrites data; read-only `TenantIntegrityReporter`; admin-only `GET /api/admin/tenantless-users` and `POST /api/admin/users/{id}/tenant`; prod JWT guard; secrets removed from tracked env files; `setup.sh` generates secrets; gitleaks config + PR scan workflow. Committed secrets treated as compromised (history not rewritten).
- **C — authz:** shared `UserAccountValidator` (single create = bulk import), scoped control-room/center/region listings, stale grants removed on center move, maintenance-type tenant scoping, `/api/platform/tenants/**` platform admin only.
- **D — workflow:** assign 400 reproduced: causes were `/transition` to `assigned` (H1), `technicianId` in a JSON body (H2), non-UUID id (H3); all handled (assign accepts query or body; wrong-endpoint transition now 422). Return-for-information (`current_stage = info_requested`), SLA escalation notifications, non-blocking duplicate-candidate warning (filtered by access policy), history row per action.
- **Coordinator fixes after verification:** inactive technician cannot be assigned; case-insensitive username duplicate check (was 500 on the V8 index); `DataIntegrityViolationException` → 409; tenant-assignment endpoints restricted to the platform administrator.
- **Tests:** 620 backend unit tests, 0 failures (baseline 483). Isolated throwaway stack (fresh DB V1-V8; V2-era DB with data; two tenants; all roles): DEF-005 matrix 173/173, writes/legit-flow/WS-handshake 68/68, admin rules 260/268 (3 defects fixed afterwards, 3 harness expectation errors, 2 of the 8 are not defects), child-row tenant check PASS, attachments PASS, WebSocket after connection (open ≥5 s, ASSIGNED frame to the right technician, nothing to others/other tenant, bad tokens refused) PASS. Frontend: tsc shows only the 4 pre-existing errors; next build exits 0 (but `ignoreBuildErrors` is set, so it does not type-check). NOT re-run after the last small fixes in the throwaway stack (unit tests only).
- **Known gaps:** (a) V7 runs after V4, so on a DB initialised only from init.sql+V2 (never started with the old DataInitializer) users/work orders of the seed center stay tenant-less; users are fixable via the admin endpoint, work orders are not (platform admin only). (b) Rows carrying a *wrong* tenant are neither corrected nor reported. (c) Duplicate control room of the same center/type now returns 409 instead of 500 only via the generic integrity handler. (d) Escalation writes no history row (no system actor). (e) SLA not paused while info is pending; lock/unlock write no history. (f) `next.config.mjs` ignores build type errors.
- **Not verified:** the user's Codespace (never accessed); real GitHub Actions run of secret-scan; browser/Safari UI; DB-backed behaviour of the new derived repository queries beyond the isolated stack run.
- **Next milestone (M4):** see `roadmap.md` acceptance criteria. Immediate prerequisite: owner approval, a database backup, then deploy to the Codespace.
