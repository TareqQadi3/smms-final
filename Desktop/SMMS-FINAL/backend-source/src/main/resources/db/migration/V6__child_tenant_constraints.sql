-- V6: indexes, foreign keys and provably safe constraints.
-- Every constraint is added NOT VALID (enforced for new rows) and then validated inside a guarded
-- sub-block, so a legacy violating row only produces a WARNING and never blocks startup.
-- Idempotent: re-running on a hand-fixed DB is safe.

CREATE INDEX IF NOT EXISTS idx_ticket_history_tenant ON ticket_history(tenant_id);
CREATE INDEX IF NOT EXISTS idx_attachments_tenant    ON attachments(tenant_id);
CREATE INDEX IF NOT EXISTS idx_notifications_tenant  ON notifications(tenant_id);

DO $$
DECLARE
  c record;
  nulls bigint;
  tenantless_wo bigint;
BEGIN
  -- 1. Foreign keys to tenants, and CHECKs. (table, constraint name, definition)
  FOR c IN
    SELECT * FROM (VALUES
      ('ticket_history', 'fk_ticket_history_tenant', 'FOREIGN KEY (tenant_id) REFERENCES tenants(id)'),
      ('attachments',    'fk_attachments_tenant',    'FOREIGN KEY (tenant_id) REFERENCES tenants(id)'),
      ('notifications',  'fk_notifications_tenant',  'FOREIGN KEY (tenant_id) REFERENCES tenants(id)'),
      ('attachments',    'attachments_file_size_nonneg',
         'CHECK (file_size IS NULL OR file_size >= 0)'),
      ('work_orders',    'work_orders_closed_after_created',
         'CHECK (closed_at IS NULL OR closed_at >= created_at)'),
      ('work_orders',    'work_orders_completed_after_created',
         'CHECK (completed_date IS NULL OR completed_date >= created_at)'),
      ('work_orders',    'work_orders_sla_after_created',
         'CHECK (sla_deadline IS NULL OR sla_deadline >= created_at)')
    ) AS v(tbl, cname, def)
  LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = c.cname AND conrelid = to_regclass('public.' || c.tbl)) THEN
      EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I %s NOT VALID', c.tbl, c.cname, c.def);
    END IF;
    BEGIN
      EXECUTE format('ALTER TABLE %I VALIDATE CONSTRAINT %I', c.tbl, c.cname);
    EXCEPTION WHEN foreign_key_violation OR check_violation THEN
      RAISE WARNING 'V6: constraint % on % stays NOT VALID (legacy rows violate it; enforced for new rows only)',
        c.cname, c.tbl;
    END;
  END LOOP;

  -- 2. NOT NULL on ticket_history and attachments, only when it is verifiably safe:
  --    no NULL in the table AND no tenant-less work order that could still receive rows.
  SELECT count(*) INTO tenantless_wo FROM work_orders WHERE tenant_id IS NULL;

  FOR c IN SELECT * FROM (VALUES ('ticket_history'), ('attachments')) AS v(tbl) LOOP
    EXECUTE format('SELECT count(*) FROM %I WHERE tenant_id IS NULL', c.tbl) INTO nulls;
    IF nulls = 0 AND tenantless_wo = 0 THEN
      EXECUTE format('ALTER TABLE %I ALTER COLUMN tenant_id SET NOT NULL', c.tbl);
    ELSE
      RAISE WARNING 'V6: % has % NULL tenant rows and % tenant-less work orders exist; NOT NULL deferred (follow-up F-A1)',
        c.tbl, nulls, tenantless_wo;
    END IF;
  END LOOP;

  -- 3. Notifications stay nullable (platform notifications). A notification tied to a work order
  --    must carry a tenant, but only enforce that when no tenant-less work order exists.
  IF tenantless_wo = 0 THEN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'notifications_tenant_when_work_order'
                      AND conrelid = to_regclass('public.notifications')) THEN
      ALTER TABLE notifications ADD CONSTRAINT notifications_tenant_when_work_order
        CHECK (tenant_id IS NOT NULL OR work_order_id IS NULL) NOT VALID;
    END IF;
    BEGIN
      ALTER TABLE notifications VALIDATE CONSTRAINT notifications_tenant_when_work_order;
    EXCEPTION WHEN check_violation THEN
      RAISE WARNING 'V6: notifications_tenant_when_work_order stays NOT VALID (legacy rows violate it)';
    END;
  ELSE
    RAISE WARNING 'V6: % tenant-less work orders exist; notifications_tenant_when_work_order deferred', tenantless_wo;
  END IF;
END $$;
