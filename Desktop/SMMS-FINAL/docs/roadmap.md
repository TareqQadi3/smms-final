# SMMS roadmap (planned, not approved features)

Last updated 2026-10-23. Items marked "Planned" are not implemented.

## Next milestone (M4) — deploy-readiness and mobile API groundwork
Acceptance criteria:
1. Branch `claude/security-phase-3` reviewed and merged into the integration branch by the owner; deployed to the Codespace only after a database backup and explicit approval.
2. `setup.sh` run on the Codespace replaces the leaked JWT secret; all users re-login; admin lists tenant-less users (`GET /api/admin/tenantless-users`) and assigns tenants or confirms none.
3. Flyway reports V1..V8 applied on the Codespace DB; any V6 deferred NOT NULL/CHECK follow-up (F-A1) cleared after the tenant-less work orders are fixed.
4. DONE on `claude/security-phase-4`: attachment download, upload Idempotency-Key, refresh rotation + logout revocation, browser WebSocket tickets. Remaining: push after DB commit, Redis-backed ticket store for multiple replicas.
7. Seed the 15 test accounts only with explicit authorization; start the QA campaign (`qa/test-plan.md`).

## Planned: Technician mobile app (separate milestone, not started)
Dedicated app for field technicians on the existing backend (no second backend). Contract: `mobile-technician-api.md`. Backend prerequisites (download, idempotency, rotation, WS tickets) exist on `claude/security-phase-4`; the client must single-flight refresh.

## Planned: Inventory deduction and purchasing (M5, not started)
**Foundation present:**
- Tables `inventory_items` (center-scoped, `UNIQUE(center_id, sku)`, `quantity NUMERIC(12,2)`, `reorder_level`) and `inventory_movements` (item, optional work_order, actor, type enum `stock_in|stock_out|adjustment`, quantity, note). Both have tenant_id, which A's V4 backfills from the parent.
- Entities and DTOs (`CreateInventoryItemRequest`, `InventoryMovementRequest`, `InventoryItemResponse`) exist.
- Work-order fields `needsMaterials`, `warehouseNotes`, `materialRequiredAt`; approval decision `purchase_request`; warehouse and approval endpoints exist.
- `assets` exists with `department_id`.

**Missing:** repositories, services, controllers and UI; a CHECK `quantity >= 0`; a movement `reversal_of` link; purchase tables.

**Plan (milestone M5, after mobile MVP M4):**
1. **Items API** (warehouse, managers): CRUD within center scope via AdminScopePolicy-style checks; low-stock list (`quantity <= reorder_level`).
2. **Consumption**: `POST /work-orders/{id}/materials {itemId, qty}` (technician on own in-progress ticket, or warehouse). Implementation:
   - One transaction: `SELECT ... FOR UPDATE` on the item; `IF quantity >= qty` (the CLAUDE.md §2 rule, commented); decrement; insert a `stock_out` movement linked to the work order; history note on the work order.
   - Insufficient stock → 409 and suggest `waiting_materials`.
3. **Reversal**: new movement `stock_in` with `reversal_of` (migration adds the nullable FK), allowed until the work order is closed; never update or delete movements. Movements are append-only (revoke UPDATE/DELETE at app level; DB trigger later).
4. **Receiving**: `stock_in` by warehouse with a supplier reference; adjustments need maintenance_manager and a reason.
5. **Purchasing**:
   - New `purchase_requests` (work_order, item or free text, qty, status `requested|approved|ordered|received|cancelled`, approver) and `purchase_request_lines`.
   - Approval by operating_company_manager reuses the `purchase_request` decision.
   - Receiving creates `stock_in` movements and resumes the work order to `in_progress` via the warehouse endpoint.
6. **Audit**: every movement has actor, time, work order and reason; a stock ledger report per item (sum of movements must equal `quantity`, nightly check).
7. **Permissions**: warehouse (items, receive, issue), technician (consume on own ticket), operating_company_manager (approve purchases), managers (reports), resident/department (none).
8. **Migrations** via Flyway, with these CHECKs: `quantity >= 0`, movement `quantity > 0`.

**Roadmap doc also holds:**
- M4 Mobile MVP. Acceptance:
  - every endpoint in section 6 is documented and probe-tested
  - attachment download plus the idempotency key are implemented
  - the WS checklist in 7.4 passes
  - refresh rotation is decided
- M3 acceptance (below).

**Next-milestone acceptance criteria:**
- 0 test failures
- the two-tenant probe passes
- no NULL child tenant except the documented legacy rows
- gitleaks is clean in CI
- `setup.sh` passes on a fresh clone


## Other open items
- Department-user link (`users.department_id`), see `security/phase2-proposals.md`.
- CHECK constraints beyond V6, per-tenant uniqueness of center/region codes.
- Escalation history row needs a system actor (actor columns are NOT NULL); SLA is not paused while info is pending; lock/unlock write no history.
- Duplicate detection is a non-blocking warning only.
