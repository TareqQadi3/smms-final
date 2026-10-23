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
