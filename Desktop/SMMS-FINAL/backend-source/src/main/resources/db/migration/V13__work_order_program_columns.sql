-- V13: shared work-order / user columns for feature program 1 (workflow, assets, reports).
-- Additive only. Columns are mapped up front in WorkOrder.java / User.java so the later
-- workstream migrations (V20+) never need to touch those hot entities.

ALTER TABLE work_orders
  -- optimistic locking (@Version): two concurrent transitions can no longer both win
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS asset_id UUID REFERENCES assets(id),
  ADD COLUMN IF NOT EXISTS department_id UUID REFERENCES departments(id),
  -- SLA pause (request for information): non-null sla_paused_at means the clock is paused
  ADD COLUMN IF NOT EXISTS sla_paused_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS sla_paused_seconds BIGINT NOT NULL DEFAULT 0 CHECK (sla_paused_seconds >= 0),
  ADD COLUMN IF NOT EXISTS is_emergency BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS emergency_declared_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS emergency_declared_by_id UUID REFERENCES users(id),
  ADD COLUMN IF NOT EXISTS requester_confirmation VARCHAR(10)
      CHECK (requester_confirmation IN ('accepted', 'disputed')),
  ADD COLUMN IF NOT EXISTS requester_confirmed_at TIMESTAMPTZ;

ALTER TABLE users ADD COLUMN IF NOT EXISTS department_id UUID REFERENCES departments(id);

CREATE INDEX IF NOT EXISTS idx_wo_asset ON work_orders(asset_id) WHERE asset_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_wo_department ON work_orders(department_id) WHERE department_id IS NOT NULL;
-- reports: range scans per tenant
CREATE INDEX IF NOT EXISTS idx_wo_tenant_created ON work_orders(tenant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_wo_tenant_completed ON work_orders(tenant_id, completed_date)
  WHERE completed_date IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_users_department ON users(department_id) WHERE department_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_history_wo_created ON ticket_history(work_order_id, created_at);

-- An emergency cannot be declared before the ticket exists. Added NOT VALID then validated:
-- every legacy row has a NULL emergency_declared_at, so the validation cannot fail.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_wo_emergency_after_created') THEN
    ALTER TABLE work_orders ADD CONSTRAINT chk_wo_emergency_after_created
      CHECK (emergency_declared_at IS NULL OR emergency_declared_at >= created_at) NOT VALID;
  END IF;
END $$;
ALTER TABLE work_orders VALIDATE CONSTRAINT chk_wo_emergency_after_created;
