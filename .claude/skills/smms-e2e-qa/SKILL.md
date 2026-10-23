---
name: smms-e2e-qa
description: Executes the SMMS multi-role QA campaign defined in docs/qa/test-plan.md at API and browser level, recording PASS/FAIL/BLOCKED/NOT TESTED with request references and screenshots. Use when asked to run, continue or re-run the E2E/UAT campaign, verify a scenario (A-H), or produce QA evidence.
---

# SMMS end-to-end QA

## When to use
Running the campaign in `Desktop/SMMS-FINAL/docs/qa/test-plan.md` (entry gate QA-050..053, then scenarios A-H), re-testing after a fix, or checking one role/endpoint. Model workflow note: Opus plans and reviews (scenario selection, defect triage, fix design); Sonnet executes steps and records evidence.

## Inputs / scope
- Target: a running stack (default `http://localhost`, API `http://localhost/api`), normally the Codespace.
- Accounts: usernames only, from section 2 of the test plan (`admin`, `Nasser`, `Tareq`, `cr.residential`, `cr.hospital`, `cr.it`, `cr.medical`, `tech.residential`, `tech.hospital`, `tech.it`, `tech.medical`, `maint.manager`, `elec.manager`, `warehouse`, `opco.manager`, `resident`, `department`, `other.user`). Passwords are NEVER written to any file; read at runtime from env (for example `$QA_PASS`, `$ADMIN_PASS`) or prompt the user.
- Scenario subset requested by the user (default: entry gate first).

## Relevant files (verified)
- `Desktop/SMMS-FINAL/docs/qa/test-plan.md` (roles, permission matrix, workflow, QA IDs, GAP IDs)
- `Desktop/SMMS-FINAL/scripts/seed-test-accounts.sh` (dry run by default; `--apply` creates only missing accounts; refuses writes outside a Codespace unless `SMMS_ALLOW_NON_CODESPACE=1`; `--reset-password` is a risky option, see safety)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/controller/WorkOrderController.java`, `AuthController.java`, `UserController.java` (endpoints under test)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/service/WorkflowEngine.java`, `WorkOrderService.java` (expected behavior)
- `Desktop/SMMS-FINAL/frontend/src/app/` (pages), `Desktop/SMMS-FINAL/frontend/src/lib/api.ts` (client calls)
- `Desktop/SMMS-FINAL/nginx/nginx.conf` (public entry: `/api/`, `/ws/`, `/uploads/`)
- Outputs (relative to `Desktop/SMMS-FINAL`; create if absent): `docs/qa/test-results.md`, `docs/qa/evidence/`

## Procedure
1. **Pre-flight.** `curl -s -o /dev/null -w "%{http_code}\n" http://localhost/health` should be 200; if the app is down, mark everything BLOCKED and stop (do not start/stop services unless asked). Read the test plan; note its "Status: plan only" header and the date.
2. **Accounts.** Dry run first: `bash Desktop/SMMS-FINAL/scripts/seed-test-accounts.sh` (prompts for the admin password, hidden). Report which accounts are missing. Run with `--apply` ONLY after the user explicitly authorizes it for the target Codespace. Never use `--reset-password` without separate approval (it puts the password in the URL, hence nginx logs).
3. **Tokens.** Log in per role via `POST /api/auth/login` with a body built from env vars; keep tokens in shell variables or a mode-600 file in the scratchpad, never echo them, never include them in results (reference requests by method, path, status, ticket number, timestamp).
4. **Entry gate.** Execute QA-050..053 exactly as written; if QA-050/051 fail, stop the campaign (the plan says to fix with regression tests first) and report BLOCKED for scenarios A-H.
5. **Per scenario step.** For each QA ID: perform it at API level (curl with `$TOKEN`), then confirm in the browser via Playwright where the plan says UI. Record: QA ID, actor username, action, expected, actual, HTTP status, request ref (e.g. `POST /api/work-orders/{id}/assign -> 200`, ticket `WO-...`), result, evidence file. Ticket data must be labelled (title prefix `QA-<id>`).
6. **Negative tests (mandatory per scenario).** Wrong-role token -> 403 (e.g. technician calling `/assign`, `resident` calling `/warehouse`); no token -> 401; invalid transition -> 422; missing title / subType -> 400; another technician's ticket -> 403/404; `.exe` upload -> 400; do NOT test the 51 MB limit by sending huge files repeatedly (one request at most, and only if authorized).
7. **GAP items.** `GAP-xx` rows are recorded as "Not implemented (code evidence: file)", never as FAIL of a delivered feature; re-confirm absence by grep before writing.
8. **Browser steps.** Use preinstalled Chromium only: `export PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers NODE_PATH=/opt/node22/lib/node_modules`; never `playwright install`. Real screenshots only, named `QA-###-<step>.png` (e.g. `QA-104-start-work.png`), saved in `Desktop/SMMS-FINAL/docs/qa/evidence/`. Do not capture login forms with typed passwords.
9. **Results file.** Append to `docs/qa/test-results.md` one table per run: `Run date | Commit | Env | QA ID | Actor | Result | Request ref | Evidence | Notes`. Results vocabulary: PASS, FAIL, BLOCKED (precondition missing, say which), NOT TESTED. Never mark PASS from code reading alone.
10. **Defects.** For each FAIL, add a finding (table below) with a minimal reproduction and severity; propose a fix only if bounded with a regression test; otherwise hand it to the Opus planner.
11. **Cleanup.** Only records created by this run (identified by the `QA-<id>` label and recorded ids), and only if the user authorized cleanup; prefer leaving closed/cancelled test tickets. Never bulk-delete, never touch seeded or user-created data (`Nasser`, `Tareq`, `admin`).

## Expected output
Run summary (counts of PASS/FAIL/BLOCKED/NOT TESTED), the results table appended to `docs/qa/test-results.md`, and findings:

| ID | Severity | File:line | Evidence | Recommendation | Verified? |
|----|----------|-----------|----------|----------------|-----------|
| QA-F-001 | High | path:line | step, status code, evidence file | fix idea | verified by run (request ref) |

## Verification
- Backend unit suite before/after any fix: `cd Desktop/SMMS-FINAL/backend-source && mvn -q test`.
- Re-run the failing QA ID and its negative test; the same evidence naming with a `-retest` suffix.
- Cross-check API result with `GET /api/work-orders/$ID/history` (one row per transition) and the UI.
- Confirm no credentials in outputs: `grep -rniE "password|bearer " Desktop/SMMS-FINAL/docs/qa | grep -v "never"` should show no values.

## Safety restrictions
Read-only by default; writes limited to labelled QA tickets and (with authorization) test accounts. Never reset/reseed/drop the DB or delete Docker volumes (`docker compose down -v`, `make clean` forbidden). Never print or commit passwords, tokens or `.env` contents; usernames only. Never make ports public. No destructive or DoS testing (no load, no repeated login failures to trigger lockout). Never merge to main. Fixes only when bounded, justified and covered by a regression test; stop and ask before schema migrations or architectural changes. Distinguish PASS/FAIL/BLOCKED/NOT TESTED; never claim a feature exists without code evidence; iPhone emulation is not a physical device.
