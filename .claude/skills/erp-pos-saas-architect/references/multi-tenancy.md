# Multi-Tenancy & RBAC Reference

## Tenancy model options

**Shared schema, `tenant_id` column (default recommendation for this project)**
- One Postgres database, one set of tables, every tenant-owned row carries `tenant_id`.
- Pros: simplest migrations, easiest cross-tenant admin/reporting, cheapest at moderate scale.
- Cons: relies on discipline — every query must filter by `tenant_id`; a missed filter leaks data across companies.
- Mitigate the con with Postgres **Row-Level Security (RLS)** policies as a hard backstop, in addition to app-level scoping. Set `app.current_tenant_id` via `SET LOCAL` per request/transaction, and write RLS policies like:
```sql
ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoices
  USING (tenant_id = current_setting('app.current_tenant_id')::uuid);
```
This means even a buggy query without an explicit WHERE clause can't cross tenants.

**Schema-per-tenant**
- Consider only if a specific client needs strong data isolation guarantees (e.g., contractual/regulatory) or very large individual tenants. Adds real migration/ops complexity (running migrations across N schemas). Don't default to this without a specific driver.

## Multi-branch model (within a tenant)

Most companies on this platform have one `tenant_id` (company) with multiple `branch_id`s (physical stores/warehouses). Model as:
- `companies` (tenant root)
- `branches` (belongs to company)
- Transactional tables (sales, stock, cash sessions) carry both `tenant_id` and `branch_id`
- Users are assigned to one or more branches, plus a role scope (see RBAC below)

## RBAC design

Three layers, don't collapse them:
1. **Role** — a named bundle of permissions (e.g., `cashier`, `branch_manager`, `accountant`, `company_admin`).
2. **Permission** — a fine-grained action (`sales.create`, `sales.void`, `inventory.adjust`, `reports.financial.view`). Store as a table, not an enum, so new permissions don't require a migration to change existing role assignments.
3. **Scope** — the branch(es)/company the role applies to. A `branch_manager` role at Branch A must not implicitly grant access at Branch B.

Suggested tables: `roles`, `permissions`, `role_permissions`, `user_roles (user_id, role_id, tenant_id, branch_id NULL for company-wide)`.

Enforce at two points:
- **Guard/decorator layer** (NestJS: custom `@RequirePermission('sales.void')` guard) — rejects before touching the DB.
- **Query layer** — every repository method that lists/reads business data takes the requesting user's tenant/branch scope as a mandatory parameter, not an optional one. Prefer a base repository/query-builder wrapper that makes it awkward to *forget* scoping, rather than relying on every developer remembering.

## Onboarding a new tenant — checklist

- Create `companies` row, default branch, default admin user, default role set (don't make the new admin manually create roles from scratch).
- Seed a default chart of accounts (accounting module) and default tax codes.
- If ZATCA-applicable: capture VAT registration number and CR number at onboarding, required before Phase 1/2 invoice generation.
