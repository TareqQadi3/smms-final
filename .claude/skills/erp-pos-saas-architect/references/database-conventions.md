# Database Design Conventions

## Standard columns on every business table

```sql
id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
tenant_id     UUID NOT NULL REFERENCES companies(id),
branch_id     UUID REFERENCES branches(id),      -- nullable only for company-wide entities
created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
created_by    UUID REFERENCES users(id),
updated_by    UUID REFERENCES users(id),
deleted_at    TIMESTAMPTZ                          -- soft delete
```

Index `(tenant_id, branch_id)` and `(tenant_id, deleted_at)` at minimum on high-traffic tables.

## Soft delete vs hard delete

- Soft delete: sales, invoices, products, customers, suppliers, purchase orders, journal entries — anything with financial, legal, or audit relevance.
- Hard delete acceptable: expired sessions, draft carts abandoned pre-checkout, notification logs past retention, temp file references.
- Always filter `deleted_at IS NULL` via a query-layer default scope, not ad-hoc per query.

## Audit log

Separate `audit_logs` table, not just `updated_by` columns, for anything where "who changed what and when" matters legally or operationally:

```sql
audit_logs (
  id, tenant_id, actor_user_id, action, entity_type, entity_id,
  before_state JSONB, after_state JSONB, reason TEXT, created_at
)
```

Always write an audit log row for: price overrides at POS, invoice voids/credit notes, manual stock adjustments, permission/role changes, refunds, discount overrides beyond a configured threshold.

## Money & quantities

- Store money as `NUMERIC(14,2)` (or minor units as `BIGINT` if you need sub-cent precision anywhere, e.g. FX) — never `FLOAT`/`REAL`.
- Store quantities as `NUMERIC` if fractional units are possible (weight-based items); `INTEGER` only if strictly whole-unit.
- Always store currency code alongside money columns if multi-currency is possible, even if only SAR is supported today — cheap to add now, painful to retrofit.

## Relationships & normalization

- Normalize transactional data (sales, purchases, stock movements) fully — don't denormalize into JSON blobs for things you'll query/report on.
- JSONB is fine for genuinely variable/unstructured data (e.g., device diagnostic notes, custom fields per tenant) — not for core relational data like line items.
- Every line-item table (`sale_items`, `purchase_order_items`, `repair_parts_used`) references the parent transaction with `ON DELETE CASCADE` only if the parent's soft-delete already covers integrity — otherwise `RESTRICT`.

## Migrations

- One logical change per migration file; never edit a migration that's already been applied in any shared environment — write a new one.
- Every migration that adds a NOT NULL column to an existing table needs a backfill step or a safe default, since tenant tables will have existing rows.
