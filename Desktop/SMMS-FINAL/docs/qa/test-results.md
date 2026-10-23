# SMMS — QA Test Results

Plan: [test-plan.md](test-plan.md) · Evidence rules: [evidence/README.md](evidence/README.md) · Context: [../project-memory.md](../project-memory.md)

**Campaign status: not started.** No test below has been executed. Never record passwords; record account usernames only.

Legend: **PASS** expected = actual · **FAIL** defect logged · **BLOCKED** cannot run (state reason) · **NOT TESTED** not yet run. GAP rows track features absent from the code; they are not counted as FAIL.

Environment for each run must be recorded (Codespace name or local copy, commit hash, date, executing model).

| QA ID | Role/account (no password) | Preconditions | Steps ref | Expected | Actual | Status | Request ref | Evidence | Defect/Fix/Retest |
|---|---|---|---|---|---|---|---|---|---|
| QA-001 | all roles | accounts verified | [§8](test-plan.md) | Valid login per role → landing page |  | NOT TESTED |  |  |  |
| QA-002 | any test account | accounts verified | [§8](test-plan.md) | Wrong password → 401 |  | NOT TESTED |  |  |  |
| QA-003 | any test account | accounts verified | [§8](test-plan.md) | Wrong-case username → 401 |  | NOT TESTED |  |  |  |
| QA-004 | any test account | accounts verified | [§8](test-plan.md) | Brute-force lockout |  | NOT TESTED |  |  |  |
| QA-005 | any test account | accounts verified | [§8](test-plan.md) | Expired/forged JWT → 401; logout |  | NOT TESTED |  |  |  |
| QA-006 | any test account | accounts verified | [§8](test-plan.md) | Deactivated user blocked |  | NOT TESTED |  |  |  |
| QA-010 | admin | account verified | [§9](test-plan.md) | menu matches §5; no blank/403 pages |  | NOT TESTED |  |  |  |
| QA-011 | Nasser | account verified | [§9](test-plan.md) | menu matches §5; no blank/403 pages |  | NOT TESTED |  |  |  |
| QA-012 | Tareq | account verified | [§9](test-plan.md) | menu matches §5; no blank/403 pages |  | NOT TESTED |  |  |  |
| QA-013 | cr.* | account verified | [§9](test-plan.md) | menu matches §5; no blank/403 pages |  | NOT TESTED |  |  |  |
| QA-014 | tech.* | account verified | [§9](test-plan.md) | menu matches §5; no blank/403 pages |  | NOT TESTED |  |  |  |
| QA-015 | maint.manager | account verified | [§9](test-plan.md) | menu matches §5; no blank/403 pages |  | NOT TESTED |  |  |  |
| QA-016 | elec.manager | account verified | [§9](test-plan.md) | menu matches §5; no blank/403 pages |  | NOT TESTED |  |  |  |
| QA-017 | warehouse | account verified | [§9](test-plan.md) | menu matches §5; no blank/403 pages |  | NOT TESTED |  |  |  |
| QA-018 | opco.manager | account verified | [§9](test-plan.md) | menu matches §5; no blank/403 pages |  | NOT TESTED |  |  |  |
| QA-019 | resident | account verified | [§9](test-plan.md) | menu matches §5; no blank/403 pages |  | NOT TESTED |  |  |  |
| QA-020 | department | account verified | [§9](test-plan.md) | menu matches §5; no blank/403 pages |  | NOT TESTED |  |  |  |
| QA-021 | other.user | account verified | [§9](test-plan.md) | menu matches §5; no blank/403 pages |  | NOT TESTED |  |  |  |
| QA-050 | resident | accounts verified | [§6](test-plan.md) | UI submission accepted (201) |  | NOT TESTED |  |  |  |
| QA-051 | cr.residential | accounts verified | [§6](test-plan.md) | new→under_review works in UI |  | NOT TESTED |  |  |  |
| QA-052 | opco.manager | accounts verified | [§6](test-plan.md) | /approvals lists waiting_approval tickets |  | NOT TESTED |  |  |  |
| QA-053 | cr.* / API | accounts verified | [§6](test-plan.md) | create response has ticketNumber & slaDeadline |  | NOT TESTED |  |  |  |
| QA-101 | resident | QA-050/051 pass | [§7A](test-plan.md) | 201, new, WO-YYYY-RESIDENTIAL-n |  | NOT TESTED |  |  |  |
| QA-102 | cr.residential | QA-101 | [§7A](test-plan.md) | under_review + history |  | NOT TESTED |  |  |  |
| QA-103 | cr.residential | QA-102 | [§7A](test-plan.md) | assigned; tech notified |  | NOT TESTED |  |  |  |
| QA-104 | tech.residential | QA-103 | [§7A](test-plan.md) | in_progress |  | NOT TESTED |  |  |  |
| QA-105 | tech.residential | QA-104 | [§7A](test-plan.md) | completed, completedDate |  | NOT TESTED |  |  |  |
| QA-106 | cr.residential | QA-105 | [§7A](test-plan.md) | closed; closedAt ≥ createdAt |  | NOT TESTED |  |  |  |
| QA-201 | department | prev step | [§7B](test-plan.md) | new |  | NOT TESTED |  |  |  |
| QA-202 | cr.hospital | prev step | [§7B](test-plan.md) | assigned |  | NOT TESTED |  |  |  |
| QA-203 | tech.hospital | prev step | [§7B](test-plan.md) | waiting_materials |  | NOT TESTED |  |  |  |
| QA-204 | warehouse | prev step | [§7B](test-plan.md) | in_progress, notes saved |  | NOT TESTED |  |  |  |
| QA-205 | tech.hospital → cr.hospital | prev step | [§7B](test-plan.md) | closed |  | NOT TESTED |  |  |  |
| GAP-B1 | — | — | [§7B](test-plan.md) | Stock check/deduction — not implemented |  | NOT TESTED |  |  |  |
| QA-301 | department | prev step | [§7C](test-plan.md) | new |  | NOT TESTED |  |  |  |
| QA-302 | cr.it / tech.it | prev step | [§7C](test-plan.md) | waiting_materials |  | NOT TESTED |  |  |  |
| QA-303 | warehouse | prev step | [§7C](test-plan.md) | waiting_approval |  | NOT TESTED |  |  |  |
| QA-304 | opco.manager | prev step | [§7C](test-plan.md) | listed in /approvals (no notification expected) |  | NOT TESTED |  |  |  |
| QA-305 | opco.manager | prev step | [§7C](test-plan.md) | purchase_request → in_progress |  | NOT TESTED |  |  |  |
| QA-306 | tech.it → cr.it | prev step | [§7C](test-plan.md) | closed |  | NOT TESTED |  |  |  |
| GAP-C1 | — | — | [§7C](test-plan.md) | Procurement/receipt/inventory — not implemented |  | NOT TESTED |  |  |  |
| QA-401 | setup | prev step | [§7D](test-plan.md) | ticket at waiting_approval |  | NOT TESTED |  |  |  |
| QA-402 | opco.manager | prev step | [§7D](test-plan.md) | rejected → waiting_materials, tech notified |  | NOT TESTED |  |  |  |
| QA-403 | warehouse | prev step | [§7D](test-plan.md) | in_progress |  | NOT TESTED |  |  |  |
| QA-404 | tech | prev step | [§7D](test-plan.md) | completed |  | NOT TESTED |  |  |  |
| QA-405 | cr.* | prev step | [§7D](test-plan.md) | reopen → in_progress, reopenedCount=1 |  | NOT TESTED |  |  |  |
| QA-406 | tech → cr.* | prev step | [§7D](test-plan.md) | closed |  | NOT TESTED |  |  |  |
| GAP-D1 | — | — | [§7D](test-plan.md) | Reassign after assigned — not implemented |  | NOT TESTED |  |  |  |
| QA-501 | cr.medical | prev step | [§7E](test-plan.md) | critical; slaDeadline ≈ +4h |  | NOT TESTED |  |  |  |
| QA-502 | cr.medical / tech.medical | prev step | [§7E](test-plan.md) | normal flow to closed |  | NOT TESTED |  |  |  |
| QA-503 | observer | prev step | [§7E](test-plan.md) | slaBreached=true after deadline |  | NOT TESTED |  |  |  |
| GAP-E1 | — | — | [§7E](test-plan.md) | Critical escalation — not implemented |  | NOT TESTED |  |  |  |
| QA-601 | Tareq | ticket in state | [§7F](test-plan.md) | cancel new ticket, reason saved |  | NOT TESTED |  |  |  |
| QA-602 | maint.manager | ticket in state | [§7F](test-plan.md) | cancel under_review |  | NOT TESTED |  |  |  |
| QA-603 | department | ticket in state | [§7F](test-plan.md) | no actions on cancelled |  | NOT TESTED |  |  |  |
| GAP-F1 | — | — | [§7F](test-plan.md) | Return-for-info + resubmit — not implemented |  | NOT TESTED |  |  |  |
| QA-701 | resident | account verified | [§7G](test-plan.md) | 201 to residential CR |  | NOT TESTED |  |  |  |
| QA-702 | resident | account verified | [§7G](test-plan.md) | 403 to hospital CR |  | NOT TESTED |  |  |  |
| QA-703 | resident | account verified | [§7G](test-plan.md) | can track own request in UI |  | NOT TESTED |  |  |  |
| QA-704 | resident | account verified | [§7G](test-plan.md) | API list returns own tickets only |  | NOT TESTED |  |  |  |
| QA-801 | department | account verified | [§7H](test-plan.md) | each sub-type per CR |  | NOT TESTED |  |  |  |
| QA-802 | department | account verified | [§7H](test-plan.md) | 400 on missing fields |  | NOT TESTED |  |  |  |
| QA-803 | department | account verified | [§7H](test-plan.md) | jpg/pdf upload ok |  | NOT TESTED |  |  |  |
| QA-804 | department | account verified | [§7H](test-plan.md) | .exe / >50MB rejected |  | NOT TESTED |  |  |  |
| QA-805 | any | account verified | [§7H](test-plan.md) | attachment list w/o internals, no 500 |  | NOT TESTED |  |  |  |
| QA-806 | department | account verified | [§7H](test-plan.md) | duplicate behaviour documented |  | NOT TESTED |  |  |  |
| QA-807 | other.user | account verified | [§7H](test-plan.md) | 403 on submit (confirm intent) |  | NOT TESTED |  |  |  |
| QA-808 | technician | account verified | [§7H](test-plan.md) | 403 on submit via API |  | NOT TESTED |  |  |  |
| QA-901 | technician, control_room | tokens obtained | [§10](test-plan.md) | 403 warehouse action |  | NOT TESTED |  |  |  |
| QA-902 | warehouse, admin | tokens obtained | [§10](test-plan.md) | 403 approval |  | NOT TESTED |  |  |  |
| QA-903 | technician | tokens obtained | [§10](test-plan.md) | 403 on unassigned ticket |  | NOT TESTED |  |  |  |
| QA-904 | Tareq | tokens obtained | [§10](test-plan.md) | 403 creating system_admin |  | NOT TESTED |  |  |  |
| QA-905 | Tareq | tokens obtained | [§10](test-plan.md) | 403 reset-password |  | NOT TESTED |  |  |  |
| QA-906 | resident + cr.* | tokens obtained | [§10](test-plan.md) | lock behaviour documented |  | NOT TESTED |  |  |  |
| QA-907 | Nasser | tokens obtained | [§10](test-plan.md) | 403 create region |  | NOT TESTED |  |  |  |
| QA-908 | resident/department/other | tokens obtained | [§10](test-plan.md) | list scope recorded |  | NOT TESTED |  |  |  |
| QA-951 | supervisors / cr.* | BLOCKED — needs authorization | [§11](test-plan.md) | 2nd region/center isolation |  | BLOCKED |  |  |  |
| QA-952 | supervisors / cr.* | BLOCKED — needs authorization | [§11](test-plan.md) | 2nd tenant isolation |  | BLOCKED |  |  |  |
| QA-953 | supervisors / cr.* | account verified | [§11](test-plan.md) | cross-CR access within center |  | NOT TESTED |  |  |  |
| QA-971 | several | app reachable | [§13](test-plan.md) | desktop 1440×900 RTL |  | NOT TESTED |  |  |  |
| QA-972 | several | app reachable | [§13](test-plan.md) | iPhone 390×844 emulation |  | NOT TESTED |  |  |  |
| QA-973 | several | app reachable | [§13](test-plan.md) | physical iPhone Safari (user) |  | NOT TESTED |  |  |  |
| QA-981 | admin (disposable account) | account verified | [§12](test-plan.md) | reset password not leaked to logs |  | NOT TESTED |  |  |  |
| QA-982 | tech / cr | account verified | [§12](test-plan.md) | scheduledDate before creation rejected |  | NOT TESTED |  |  |  |
| QA-983 | — | account verified | [§12](test-plan.md) | stock check — not implemented |  | NOT TESTED |  |  |  |
| QA-984 | — | account verified | [§12](test-plan.md) | status+history atomic |  | NOT TESTED |  |  |  |
| QA-985 | department | account verified | [§12](test-plan.md) | XSS sanitized |  | NOT TESTED |  |  |  |
| QA-986 | department | account verified | [§12](test-plan.md) | path traversal rejected |  | NOT TESTED |  |  |  |

## Defects (found by code review)

Status for all: **open — found by code review, not yet reproduced in browser.**

| ID | Severity | Finding | Evidence (file:line) | Linked QA |
|---|---|---|---|---|
| DEF-001 | High | New-request form sends maintenance-type display name (e.g. `Electrical`, `Civil Works`) as `subType`; backend enum values are lowercase (`electrical`, `civil`, …) → submission likely 400 | `frontend/src/app/new-request/page.tsx:175,78`; `backend-source/.../enums/SubType.java`; seeds `config/DataInitializer.java:129-146` | QA-050 |
| DEF-002 | High | `TicketStatus` JSON: only `NEW` has `@JsonProperty`; others (de)serialize as `UNDER_REVIEW` etc. while UI uses lowercase → transitions/filters likely fail | `backend-source/.../enums/TicketStatus.java:5-15`; `frontend/src/app/work-orders/[id]/page.tsx:145-168`; `approvals/page.tsx:32` | QA-051, QA-052 |
| DEF-003 | Medium | Trigger-generated `ticket_number`/`sla_deadline` not re-read after insert → null in create response and "new ticket" notification | `entity/WorkOrder.java:17`; `scripts/init.sql:226-266`; `service/WorkOrderService.java:83-86`; `NotificationService.java:28-29` | QA-053 |
| DEF-004 | High (security) | Admin password reset passes `newPassword` in URL query → stored in nginx access logs | `frontend/src/lib/api.ts:76`; `controller/UserController.java:85-90` | QA-981 |
| DEF-005 | High (privacy) | Work-order list/detail scoped only by tenant (and technician assignment); resident/department/other can read every ticket in the tenant; supervisors not limited to their region/center | `service/WorkOrderService.java:252-276`; `security/TenantSecurityService.java:105-121` | QA-704, QA-908, QA-951 |
| DEF-006 | Medium | Any authenticated user can lock any ticket; lock blocks others' `/transition` but not assign/warehouse/approval/close | `controller/WorkOrderController.java:115-125`; `service/WorkOrderService.java:94,221-241` | QA-906 |
| DEF-007 | Medium | UI offers transitions the engine rejects: technician waiting_materials→in_progress; maintenance_manager in_progress→completed | `frontend/src/app/work-orders/[id]/page.tsx:150-158` vs `service/WorkflowEngine.java:36-45` | QA-104…QA-205 |
| DEF-008 | Medium | `GET /attachments` returns raw JPA entities with LAZY `User`/`WorkOrder` (open-in-view=false) → likely serialization error, or exposure of user fields; also exposes server file path | `controller/WorkOrderController.java:141-145`; `entity/Attachment.java`; `application.yml:5` | QA-805 |
| DEF-009 | Low | `notifyApprover` is empty → operating-company manager not notified of pending approvals | `service/NotificationService.java:39-41` | QA-304 |
| DEF-010 | Low | `other` and `technician` allowed by `@PreAuthorize` to create tickets but always rejected by service; `other` has no menu items | `controller/WorkOrderController.java:36`; `service/WorkOrderService.java:340-350`; `Sidebar.tsx:34-110` | QA-807, QA-808, QA-021 |
| DEF-011 | Low | No `scheduledDate` validation (can precede creation) | `service/WorkOrderService.java:323-325` | QA-982 |
| DEF-012 | Low | Warehouse lands on `/dashboard` but `/api/dashboard/*` excludes warehouse | `login/page.tsx:51`; `controller/DashboardController.java:20-33` | QA-017 |


### Defect status update (2026-10-23)

QA rows above remain NOT TESTED; the QA campaign has not started. Only defect statuses changed:

| ID | Status |
|---|---|
| DEF-001 | Fixed in `35a0edf` — verified on local sandbox API (not yet in Codespace) |
| DEF-002 | Fixed in `35a0edf` — verified on local sandbox API (not yet in Codespace) |
| DEF-003 | Fixed in `35a0edf` — verified on local sandbox API (not yet in Codespace) |
| DEF-004 | Fixed in `c46cafb` — verified on local sandbox API (not yet in Codespace) |
| DEF-006 | Fixed in `35a0edf` — verified on local sandbox API (not yet in Codespace) |
| DEF-005 | Open — needs product visibility rule |
| DEF-007, DEF-008, DEF-009, DEF-010, DEF-011, DEF-012 | Status not re-verified in this update; treat as open. Note `35a0edf` also tightened generic `/transition` rules, which may affect DEF-007 (unverified). |

Audit finding IDs fixed in the same commits (SEC-002/003/004/006/008/009) are tracked in [test-plan.md](test-plan.md) "Updates after audit/fixes". Findings files themselves lived in the sandbox scratchpad and were not committed.
