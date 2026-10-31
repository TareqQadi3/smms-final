---
name: smms-maintenance-workflows
description: Documents and checks the real SMMS work-order lifecycle (statuses, transitions, roles, approval, SLA, reopen, lock, audit, notifications) and maps business scenarios to Implemented / Partial / Not implemented with code evidence. Use when asked how a scenario works, whether a workflow stage exists, or before writing workflow tests or changing WorkflowEngine/WorkOrderService.
---

# SMMS maintenance workflows

## When to use
Questions like "does the system support procurement / returns / reassignment?"; reviewing a change to status handling; designing workflow tests; reconciling the business description with the code.

## Inputs / scope
The scenario(s) to map. Source of truth is the CODE, then `Desktop/SMMS-FINAL/CLAUDE.md` for rules. Never invent a stage that the code does not contain: record it as a GAP with the file that shows its absence.

## Relevant files (verified)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/enums/TicketStatus.java` (9 statuses), `ApprovalDecision.java`, `TicketPriority.java`, `UserRole.java`, `SubType.java`, `ControlRoomType.java`
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/service/WorkflowEngine.java` (transition map + role sets; `system_admin` may do any structurally valid transition)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/service/WorkOrderService.java` (`create`, `transition`, `assign`, `warehouseAction`, `approvalDecision`, `close`, `reopen`, `lock/unlock`, `search`, `getHistory`, `recordHistory`, `applyTransitionSideEffects`, `validateSubmitterRole`)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/controller/WorkOrderController.java` (endpoints and `@PreAuthorize`)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/service/NotificationService.java` (`notifyApprover` is an empty body), `scheduler/SlaScheduler.java` (5-minute breach flag)
- `Desktop/SMMS-FINAL/backend-source/src/main/resources/application-prod.yml` (`app.sla.*` from `SLA_CRITICAL_HOURS`, `SLA_HIGH_HOURS`, `SLA_MEDIUM_HOURS`, `SLA_LOW_HOURS`)
- `Desktop/SMMS-FINAL/scripts/init.sql` (SLA and ticket-number triggers)
- `Desktop/SMMS-FINAL/backend-source/src/test/java/com/smms/service/WorkflowEngineTest.java`, `enums/TicketStatusTest.java` (tests dir: `Desktop/SMMS-FINAL/backend-source/src/test/java/com/smms`)
- `Desktop/SMMS-FINAL/docs/qa/test-plan.md` (scenario IDs A-H, QA-xxx, GAP-xx; the plan's tables must be re-checked against code, not trusted)

## Procedure
1. **Statuses.** Read `TicketStatus`: new, under_review, assigned, in_progress, waiting_materials, waiting_approval, completed, closed, cancelled. Terminal: closed, cancelled.
2. **Transitions.** Copy the `TRANSITIONS` map from `WorkflowEngine` into your notes verbatim (from -> to -> roles). Then list the dedicated endpoints that bypass the map: `assign` (only from under_review; technician must be a `technician` in the same tenant), `warehouseAction` (waiting_materials -> in_progress if available, else waiting_approval), `approvalDecision` (approved / purchase_request -> in_progress; rejected -> waiting_materials with `rejectionReason`), `close`, `reopen` (completed -> in_progress, `reopenedCount+1`, clears `completedDate`).
3. **Overlap risks to check.** The generic `POST /{id}/transition` also accepts under_review -> assigned (control_room) with no `assignedTo`, completed -> in_progress, and the warehouse / approver moves, bypassing the dedicated endpoints' side effects (assignee, `approvedBy`, `reopenedCount`, notifications). Confirm by reading and, if possible, by a unit test; report as defect candidates.
4. **Roles per step.** Cross-check `@PreAuthorize` in `WorkOrderController` with the engine's role sets; list mismatches (e.g. `system_admin` is not allowed on `/warehouse` or `/approval`; lock/unlock/history have no role restriction).
5. **Data fields.** `needsMaterials`, `materialRequiredAt`, `warehouseNotes`, `techReport`, `approvalDecision`, `approvedBy`, `rejectionReason`, `slaDeadline`, `slaBreached`, `locked*`, `reopenedCount` in `entity/WorkOrder.java`.
6. **SLA.** DB trigger `trg_set_sla` sets the deadline by priority (critical 4h, high 8h, medium 24h, low 48h per the init script; `app.sla.*` env values exist in prod yml, verify which one actually drives the deadline). `SlaScheduler` flags breaches every 300 s and excludes completed/closed/cancelled.
7. **Audit and notifications.** Every path calls `recordHistory` (verify each of `create`, `transition`, `assign`, `warehouseAction`, `approvalDecision`, `close`, `reopen`; `lock`/`unlock` do not). Notifications: `notifyControlRoom` on create, `notifyTransition` to assignee, `notifyUser` on assign/reject, `notifyApprover` empty.
8. **Scenario mapping.** For each scenario fill: steps, actors, Status (Implemented / Partial / Not implemented), evidence `file:line`, gap. Use this baseline, then re-verify each line:
   - Routine repair, no parts: Implemented (new -> under_review -> assigned -> in_progress -> completed -> closed).
   - Part available in warehouse: Partial. Status handoff via `/warehouse` exists; no stock check or deduction (inventory entities exist, no repository/service/controller).
   - Part unavailable -> procurement -> receipt: Partial. `/warehouse` false -> waiting_approval -> `purchase_request` returns to in_progress; no purchase order, receipt or inventory update; approver not notified.
   - Failed repair / reassign / reopen: reopen Implemented; reassignment Not implemented (`assign` only from under_review); rejection loop Implemented.
   - Emergency: Partial. Only `priority=critical` with shorter SLA; no separate escalation flow.
   - Rejected / returned + resubmit: Partial. Only cancellation (terminal); no return-to-requester or resubmit.
   - Resident housing: Implemented for submission (`validateSubmitterRole`, CR access, profile has complex/building/unit fields); no requester-facing tracking list or per-requester scoping.
   - Department / other categories: Partial. Department maps to hospital/it/medical CRs; role `other` has no create permission in `validateSubmitterRole` and no menu.
9. **Never invent.** If a requested stage (procurement, QA inspection, vendor, return-for-info) is absent, write it in the gaps list with the grep that proves it: `grep -rni "procure\|purchase_order\|receipt\|resubmit" Desktop/SMMS-FINAL/backend-source/src/main`.

## Expected output
Scenario table: `Scenario | Steps (actor -> status) | Status | Evidence | Gap`.
Findings table:

| ID | Severity | File:line | Evidence | Recommendation | Verified? |
|----|----------|-----------|----------|----------------|-----------|
| WF-001 | Medium | path:line | transition bypass description | route through dedicated endpoint | verified by test / code-reading only |

Gaps are listed as `GAP-xx` (feature absent), separate from defects (feature present but wrong).

## Verification
- `cd Desktop/SMMS-FINAL/backend-source && mvn -q test` (`WorkflowEngineTest`); new transition rules need a table-driven test (from, to, role, expected).
- Live check, token from env (never printed): `curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"targetStatus":"under_review"}' http://localhost/api/work-orders/$ID/transition`. Note the status JSON casing issue recorded as QA-051 in `Desktop/SMMS-FINAL/docs/qa/test-plan.md`; confirm against the running app before reporting.
- History: `GET /api/work-orders/$ID/history` must show one row per transition.

## Safety restrictions
Read-only by default; live probes only on tickets you created for the test. Never reset/reseed/drop the DB or delete Docker volumes (`docker compose down -v` forbidden). Never print or commit passwords, tokens or `.env` contents. Never make ports public. No destructive or DoS testing. Never merge to main. Fixes only when bounded, justified and covered by a regression test; stop and ask before schema migrations or architectural changes (new statuses, procurement, inventory are architectural). Distinguish PASS/FAIL/BLOCKED/NOT TESTED; never claim a feature exists without code evidence.
