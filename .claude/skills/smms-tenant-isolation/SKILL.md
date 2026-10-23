---
name: smms-tenant-isolation
description: Audits SMMS application-level multi-tenant and role-scope isolation (tenant_id, TenantContext, TenantSecurityService, region/center scoping, technician own-orders-only). Use when reviewing any query, endpoint or filter change that touches tenant data, or when asked whether one organization can see another's data.
---

# SMMS tenant and scope isolation

## When to use
A new repository query, controller, scheduler or service; a suspected cross-tenant/cross-region leak; a periodic isolation audit. Combine with `smms-security-review` for token/JWT aspects.

## Inputs / scope
Target branch/diff, base URL (default `http://localhost/api`), tokens per role read from env at runtime. Isolation layers to check: (1) tenant (`tenant_id`), (2) region/center for `region_supervisor` / `hospital_supervisor`, (3) control-room membership for `resident` / `department`, (4) technician sees only own assigned orders (`Desktop/SMMS-FINAL/CLAUDE.md` section 1).

## Relevant files (verified)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/entity/BaseEntity.java` (`tenant_id` nullable; NULL = platform-level or pre-backfill)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/util/TenantContext.java` (three ThreadLocals, `clear()` uses `remove()`)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/security/JwtAuthFilter.java` (sets context from the JWT `tenantId` claim, never from client; `finally { TenantContext.clear(); }`)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/security/TenantSecurityService.java` (`requireWorkOrder/User/Region/Center/ControlRoom`, `assertWorkOrderAccess`, `resolveAssignedToFilter`)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/service/` : `WorkOrderService.java` (`search` ~L253, `getActiveWorkOrder`), `UserService.java` (region/center scoping ~L124-250), `DashboardService.java`, `CenterService.java`, `RegionService.java`, `ControlRoomService.java`, `MaintenanceTypeService.java`, `NotificationService.java`, `AttachmentService.java`, `BulkImportService.java`
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/repository/` (all repositories), `controller/AdminController.java`, `controller/TenantController.java`
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/scheduler/SlaScheduler.java` (runs without a request, so no TenantContext)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/config/DataInitializer.java` (`backfillTenantId`)
- Tests: `Desktop/SMMS-FINAL/backend-source/src/test/java/com/smms/util/TenantContextTest.java`
- Seed layout: `Desktop/SMMS-FINAL/scripts/init.sql` (one region JHC, one center KFCH-JZ, four control rooms, one tenant "default")

## Procedure
1. **State the model.** Isolation is enforced in application code only. There is NO PostgreSQL row-level security (confirm: `grep -in "row level\|policy" Desktop/SMMS-FINAL/scripts/*.sql` returns nothing). RLS is a defense-in-depth option that needs a migration, so it requires explicit user approval; never add it unasked.
2. **Enumerate data access.** `grep -rn "Repository\.\(find\|get\|count\|delete\|exists\)" Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/service Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/controller` and list every call. For each: does the repository method carry `TenantId` or does the service call `tenantSecurity.require*`? Flag bare `findById`, `findAll`, `findByIdIn`, native queries (`@Query(nativeQuery`) and derived queries lacking `TenantId`.
3. **Platform-admin branch.** Every `require*` falls back to unscoped `findById` when `TenantContext.get()==null`. Confirm only `system_admin` can reach tenant NULL: (a) tokens without a `tenantId` claim (refresh tokens, see `smms-security-review`) and (b) non-admin users whose `tenant_id` is NULL (pre-backfill). Check `DataInitializer.backfillTenantId` excludes only `system_admin`.
4. **ThreadLocal hygiene.** Verify `JwtAuthFilter` clears in `finally`, that no `@Async`/scheduler/WebSocket path reads `TenantContext` (`grep -rn "TenantContext" Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/{scheduler,websocket}`), and that early `return` paths (402/403 `writeError`) happen inside the try.
5. **Write paths.** Creates must set tenant from context (`setTenantId(TenantContext.get())`), not from the DTO. Check every `Create*Request` DTO has no `tenantId` field and every referenced id (centerId, controlRoomId, assignedTo, technicianId) is resolved through `require*`, not `findById`.
6. **Scope inside a tenant.** Read `WorkOrderService.search` and `getActiveWorkOrder`: only the technician is narrowed (`resolveAssignedToFilter`); region_supervisor, hospital_supervisor, resident, department and `other` see every ticket of the tenant. Compare with the intended matrix in `Desktop/SMMS-FINAL/docs/qa/test-plan.md` (footnote 3, QA-704) and record as gaps/defect candidates, not as implemented features. Do the same for `DashboardService`, attachments, history, notifications (user-scoped by id).
7. **Technician rule.** Confirm `assertWorkOrderAccess` is invoked from `getById`, `history`, `transition`, attachments `upload/list`, `lock/unlock`, and that `GET /api/work-orders?assignedTo=<other>` is overridden.
8. **Probe matrix (API).** Build a table role x endpoint x foreign id. With seed data only one region/center exists, so cross-ORG proof is impossible without a second tenant/region/center. Creating one is a write: propose it to the user as clearly labelled TEST data (`ZZ-ISOLATION-TEST-*` names), wait for approval, and delete only what you created if authorized. Without it, run intra-tenant probes (technician A vs technician B ticket, resident vs hospital control room, region_supervisor vs system_admin user) and mark cross-tenant rows NOT TESTED.

## Expected output
| ID | Severity | File:line | Evidence | Recommendation | Verified? |
|----|----------|-----------|----------|----------------|-----------|
| TEN-001 | e.g. High | path:line | query lacks tenant predicate / probe returned 200 | add `...AndTenantId` or `require*` | by test / code-reading only |

Plus the probe matrix: rows = caller role, columns = resource owner, cells = PASS / FAIL / BLOCKED / NOT TESTED with HTTP code and request reference.

## Verification
- `cd Desktop/SMMS-FINAL/backend-source && mvn -q test` (includes `TenantContextTest`); new fixes need a Mockito/MockMvc test that sets `TenantContext.set(tenantA)` and asserts access to a tenant B entity throws `AccessDeniedException` or `ResourceNotFoundException`.
- Probe without printing tokens: `curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN_A" "http://localhost/api/work-orders/$ID_OF_B"`; expect 404/403, never 200.
- Never infer isolation from the UI hiding things; the server response is the evidence.

## Safety restrictions
Read-only by default. Never reset/reseed/drop the DB or delete Docker volumes (`docker compose down -v` forbidden). Never print or commit passwords, tokens or `.env` contents. Never make ports public. No destructive or DoS testing. Never merge to main. Test data only with user approval, labelled, and removed only if you created it and removal is authorized. Fixes only when bounded, justified and covered by a regression test; stop and ask before schema migrations (including RLS) or architectural changes. Distinguish PASS/FAIL/BLOCKED/NOT TESTED; never claim a feature exists without code evidence.
