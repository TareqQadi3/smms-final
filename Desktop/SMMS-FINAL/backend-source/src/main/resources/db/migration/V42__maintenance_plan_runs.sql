-- V42: one row per (plan, due period). UNIQUE (plan_id, due_date) is the duplicate-generation
-- guard: two job passes, two replicas or a manual "generate now" can never create two work orders
-- for the same period. Additive only.

CREATE TABLE IF NOT EXISTS maintenance_plan_runs (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID        NOT NULL REFERENCES tenants(id),
    plan_id             UUID        NOT NULL,
    due_date            DATE        NOT NULL,
    status              VARCHAR(10) NOT NULL,
    source              VARCHAR(10) NOT NULL DEFAULT 'scheduler',
    work_order_id       UUID        UNIQUE REFERENCES work_orders(id),
    error               VARCHAR(500),
    overdue_notified_at TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_plan_runs_status CHECK (status IN ('generated','skipped','failed')),
    CONSTRAINT chk_plan_runs_source CHECK (source IN ('scheduler','manual')),
    CONSTRAINT uq_plan_runs_plan_due UNIQUE (plan_id, due_date),
    CONSTRAINT fk_plan_runs_plan_tenant FOREIGN KEY (plan_id, tenant_id) REFERENCES maintenance_plans (id, tenant_id)
);

CREATE INDEX IF NOT EXISTS idx_plan_runs_overdue ON maintenance_plan_runs (due_date)
    WHERE status = 'generated' AND overdue_notified_at IS NULL;
