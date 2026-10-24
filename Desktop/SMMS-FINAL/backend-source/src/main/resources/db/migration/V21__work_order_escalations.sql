-- V21: escalation history (workflow program, WS1).
-- One row per (work order, level). The unique constraint is the duplicate-event guard: the SLA
-- scheduler, a second replica and a manual escalation all insert with ON CONFLICT DO NOTHING and
-- notify only when a row was actually inserted.

CREATE TABLE IF NOT EXISTS work_order_escalations (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id       UUID NOT NULL REFERENCES tenants(id),
  work_order_id   UUID NOT NULL REFERENCES work_orders(id),
  level           SMALLINT NOT NULL CHECK (level BETWEEN 1 AND 3),
  trigger_kind    VARCHAR(20) NOT NULL
                  CHECK (trigger_kind IN ('sla_breach', 'sla_overdue', 'manual', 'emergency', 'legacy_breach')),
  -- NULL = raised by the scheduler (there is no system user)
  triggered_by_id UUID REFERENCES users(id),
  reason          TEXT,
  -- NULL = unknown (legacy rows)
  notified_count  INT CHECK (notified_count IS NULL OR notified_count >= 0),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_escalation_wo_level UNIQUE (work_order_id, level)
);
CREATE INDEX IF NOT EXISTS idx_escalations_tenant_created ON work_order_escalations(tenant_id, created_at);

-- Backfill: every ticket already flagged sla_breached gets a level-1 'legacy_breach' row (count
-- unknown). Rows without a tenant are skipped, never guessed. The scheduler never raises level 2
-- automatically for legacy rows (avoids a notification flood on the first run after the upgrade).
DO $$
DECLARE
  inserted INT;
  skipped  INT;
BEGIN
  WITH ins AS (
    INSERT INTO work_order_escalations (tenant_id, work_order_id, level, trigger_kind, reason, notified_count, created_at)
    SELECT w.tenant_id, w.id, 1, 'legacy_breach', 'SLA breach recorded before escalation history existed', NULL,
           COALESCE(w.updated_at, now())
    FROM work_orders w
    WHERE w.sla_breached = TRUE AND w.deleted_at IS NULL AND w.tenant_id IS NOT NULL
    ON CONFLICT (work_order_id, level) DO NOTHING
    RETURNING 1
  )
  SELECT count(*) INTO inserted FROM ins;
  SELECT count(*) INTO skipped FROM work_orders
   WHERE sla_breached = TRUE AND deleted_at IS NULL AND tenant_id IS NULL;
  RAISE NOTICE 'V21 escalations: % legacy level-1 row(s) recorded, % breached ticket(s) skipped (no tenant)', inserted, skipped;
END $$;
