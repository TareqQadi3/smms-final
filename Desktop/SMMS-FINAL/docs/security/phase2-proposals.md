# Security Phase 2 — proposals and risks (NOT implemented)

Status: review-only. Planned by claude-opus-5-5. Nothing below changes schema or tooling yet; each needs product-owner approval and an isolated-DB rehearsal.

## B) Review-only proposals (do NOT implement in this phase)

### B.1 tenant_id on ticket_history / attachments / notifications (DB-001)
Facts: columns already exist (V2 `ALTER TABLE ... ADD COLUMN tenant_id UUID`), nullable, no FK, no index; entities do not map them, so new rows are NULL and only the DataInitializer default-tenant backfill fills them (wrong for tenant B). `application-prod.yml` uses `ddl-auto: validate`, so mapping the existing column is safe.
Expand / backfill / constrain:
1. **Expand (code only):** map `@Column(name="tenant_id") UUID tenantId` in the 3 entities (they do not extend BaseEntity; add the field, do not change base class). Set it on every write from the parent: history/attachment `= workOrder.tenantId`; notification `= user.tenantId` (fall back to workOrder). Add `@PrePersist` guard that throws if null for non-platform rows. Reads keep current parent-based access checks (WorkOrderAccessPolicy) - tenant column is defence in depth.
2. **Backfill (migration, idempotent, batched):** `UPDATE ticket_history h SET tenant_id = w.tenant_id FROM work_orders w WHERE h.work_order_id = w.id AND h.tenant_id IS DISTINCT FROM w.tenant_id` (same for attachments; notifications via `users.tenant_id`, then `work_orders`). Run in batches of ~10k by id range; report counts; **correct** rows previously set to the default tenant by DataInitializer (hence `IS DISTINCT FROM`, not `IS NULL`). Pre-check query lists rows whose parent itself has NULL tenant -> fix parents first.
3. **Constrain:** `CREATE INDEX CONCURRENTLY idx_<t>_tenant ON <t>(tenant_id)`; `ALTER TABLE ... ADD CONSTRAINT fk_<t>_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) NOT VALID; VALIDATE CONSTRAINT`; `ADD CONSTRAINT <t>_tenant_nn CHECK (tenant_id IS NOT NULL) NOT VALID; VALIDATE`, then `SET NOT NULL` (PG16 uses the validated CHECK to skip the scan) and drop the CHECK. Optional consistency trigger later (child tenant = parent tenant).
4. Remove these tables from `DataInitializer.backfillTenantId` once step 3 is applied.
Rollback: steps 1-2 reversible (column already existed); step 3 drop constraints.

### B.2 Department <-> user relationship
Facts: `departments` table (V2: tenant_id, center_id NOT NULL, code unique per center) and `Department` entity exist with no API; users carry free-text `department_name/supervisor/email`; `assets.department_id` FK exists; `work_orders` has no department link; DEF-005 rule = department role sees only own submissions.
Proposal (backward compatible): add nullable `users.department_id UUID REFERENCES departments(id)` (+ index) and nullable `work_orders.department_id`. Invariant: `user.department.center == user.center` and same tenant (service check + later trigger). Keep text columns as display/legacy; backfill `department_id` by matching `(center_id, lower(department_name))` against `departments(center_id, lower(name))`, creating missing department rows only after review of a dry-run report. Visibility can then be widened (product decision) to "tickets of my department" via a new `WorkOrderScope` dimension. Bulk import department template gains optional `departmentCode`. No change to role enum.

### B.3 Migration tooling + CHECK constraints (DB-002, DB-005)
- Add Flyway (`flyway-core` + `flyway-database-postgresql`, Spring Boot managed versions). `spring.flyway.baseline-on-migrate=true`, `baseline-version=2`, `locations=classpath:db/migration`. Copy `init.sql` -> `V1__init.sql` and `V2__schema_migration.sql` -> `V2__...` **byte-identical** so fresh DBs build via Flyway and existing DBs (created by docker-entrypoint) are baselined at 2 without re-running. Remove the two `docker-entrypoint-initdb.d` mounts only after Flyway is verified on a throwaway DB. Move `workflow_functions.sql` (DB-003) into `V3__workflow_functions.sql` with `CREATE OR REPLACE`. Move `DataInitializer.migrateEnums` (`ALTER TYPE user_role ADD VALUE 'other'`) into a migration (note: cannot run inside a tx block on older PG; Flyway handles with `-- flyway:executeInTransaction=false`). Keep `ddl-auto: validate` in prod; change dev from `update` to `validate`.
- CHECK constraints, each as its own migration, pattern: pre-check query (count violators) -> `ADD CONSTRAINT ... NOT VALID` -> fix/report data -> `VALIDATE CONSTRAINT`. Candidates: `work_orders closed_at >= created_at`, `completed_date >= created_at`, `sla_deadline >= created_at`, `attachments file_size >= 0 AND file_size <= <max>`, `users`: center roles have `center_id` (`CHECK (role NOT IN (...) OR center_id IS NOT NULL)`), `region_supervisor` has `region_id`, `system_admin` has NULL tenant, non-admin has non-NULL tenant (only after SEC-A backfill clean). Also replace global `UNIQUE(code)` on regions/centers with `UNIQUE(tenant_id, code)` (current global uniqueness leaks existence across tenants and blocks same codes in different tenants); service duplicate checks to become tenant-scoped at the same time.

### B.4 JWT secret rotation (SEC-011) - no values
- Treat the `JWT_SECRET` in `Desktop/SMMS-FINAL/.env.codespaces` as compromised (public git history). Never reuse it outside throwaway test environments.
- Steps: (1) generate a new >= 256-bit random secret per environment out-of-band (e.g. `openssl rand -base64 48`), store as Codespaces secret / deployment secret, inject via env only; (2) replace the committed value with a placeholder and make `setup.sh` generate a random secret into the untracked `.env` when absent; add `.env.codespaces` secret lines to gitleaks allow/deny config and a CI gitleaks step; (3) startup guard in `JwtService`: refuse to start under `prod` profile if secret length < 32 bytes or equals a known-test fingerprint (compare SHA-256 hash constant, not the value); (4) optional zero-downtime rotation: support `JWT_SECRET_PREVIOUS` for verification only during one access-token lifetime (`JWT_EXPIRY_HOURS`, default 8h) + refresh lifetime, then remove; otherwise accept forced re-login; (5) history rewrite is optional and does not un-leak - rotation is what matters; (6) record rotation date in docs (no values).

---

## C) Risks, edge cases, test impact

Risks / edge cases
1. **Frontend breakage**: removing control_room from user list breaks the technician picker until `work-orders/[id]/page.tsx` uses the new endpoint - ship backend + frontend change together. Check other pages calling `userApi.getAll` (`frontend/src/app/users`) - only supervisors/admin reach it.
2. **Error code changes**: role-hierarchy violations move from 400 (IllegalArgumentException) to 403; out-of-scope ids return 404. Frontend toasts may show different text; QA expectations must be updated.
3. **Existing NULL-tenant users** (created by platform admin before restart, or via bulk import) will be **locked out** by the new JwtAuthFilter/AuthService guard until DataInitializer's derivation backfill runs at next start. Derivation-by-center is correct; rows with no center/region and >1 tenant stay NULL and need a manual, reviewed fix (report only, no auto-assignment).
4. DataInitializer runs SQL on every startup against the user's DB; changing it changes data on the Codespace DB on next start. The derivation updates only rows where the derived tenant differs/NULL; review carefully; log counts. Do not run manually.
5. Region query `(:tenantId IS NULL OR ...)` with a null UUID parameter can fail on PostgreSQL ("could not determine data type"); moving to Specifications removes this.
6. Platform admin creating `system_admin`: stays tenant NULL (platform-wide). Tenant-scoped admin concept does not exist (DataInitializer nulls all system_admin tenants); do not introduce it now.
7. hospital_supervisor center-forcing changes UI behaviour if UI sends another center (now 404). region_supervisor with NULL region / hospital_supervisor with NULL center: all management denied (fail closed).
8. Granting CRs across centers (resident living in compound served by another center's CR) may be a real business case - Q1 below; until confirmed, system_admin can still do it.
9. Bulk import all-or-nothing changes semantics (previously partial success); frontend shows `success`/`failed` - still compatible shape. Large files: POI XSSF loads whole workbook in memory; 2 MB cap + row cap mitigates (zip-bomb: also set `ZipSecureFile.setMinInflateRatio` default stays on - do not lower).
10. Sort on `priority`/`status` uses PG enum declaration order, not alphabetical - acceptable, document.
11. Clamping size (vs 400) hides client bugs - documented choice.
12. Center/region code uniqueness is global in DB (`UNIQUE(code)`): tenant-scoped duplicate checks would still hit DB unique errors across tenants until B.3; keep current global check for now (accept the existence oracle; listed).
13. Plan limits for platform-admin-created users/centers currently skipped (tenant null in ctx); the new overloads apply limits of the derived tenant - may start rejecting creations on FREE-plan tenants.

Existing tests that will need changes (`backend-source/src/test/java/com/smms/...`)
- `service/UserServiceScopeTest` - constructor gains `AdminScopePolicy` (and possibly `TenantRepository`); `hospitalSupervisorCannotGrantSystemAdmin` now expects `AccessDeniedException` instead of `IllegalArgumentException` (check assertion type); `hospitalSupervisorCannotMoveUserToForeignCenter` may become 404 path; actors need center/region set for new same-center checks.
- `service/WorkOrderServiceTest` (lines ~219, ~238) - `search(..., 0, 20, actor)` -> Pageable signature; constructor if `UserRepository`/mapper added for assignable technicians.
- `service/NotificationServiceTest` - repo method rename (`findByUserId` + Pageable) if it stubs `findByUserIdOrderByCreatedAtDesc`.
- `service/PlanEnforcementServiceTest` - new overloads (additive; existing tests should pass).
- `security/JwtAuthFilterTest` - new rejection cases; existing fixtures with non-admin users must carry a tenant claim matching the user's tenant.
- `service/AuthServiceTest` - fixtures for non-admin users need non-null tenantId, else new guard returns 401.
- `security/WorkOrderAccessPolicyTest`, `AttachmentServiceTest`, `DashboardServiceTest`, `NotificationWebSocketHandlerTest` - should be unaffected (verify).
- New: `AdminScopePolicyTest`, `UserListAuthorizationTest`, `CenterServiceScopeTest`, `ControlRoomServiceScopeTest`, `TenantResolutionTest`, `BulkImportServiceTest`, `PageRequestsTest`, `TenantControllerTest`.

Open questions for product owner
- Q1: may a user be granted a control room outside their own center (non-admin grantor)? Default: no.
- Q2: should hospital_supervisor see other hospital_supervisors of the same center in the list? Default: yes (read-only).
- Q3: bulk import: all-or-nothing (proposed) vs partial success?
- Q4: size > 100: clamp (proposed) or 400?
- Q5: may region_supervisor create centers at all (currently allowed by `@PreAuthorize`)? Proposed: yes, only in own region.
