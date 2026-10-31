-- V9: re-derive tenant_id after the seed hierarchy received its tenant (closes gap (a)).
-- Rationale: on a compose fresh install V4 runs while no tenant exists, V7 then gives the seed
-- region/center/control rooms the default tenant. Rows created in between (or on a DB the old
-- startup code never touched) stay NULL. This repeats V4/V5/V6 for NULL rows only.
-- Safety rules (enforced by MigrationScriptsTest):
--   * every UPDATE is guarded by "tenant_id IS NULL" and takes the value from a verified parent;
--   * never a default tenant, never overwrites a non-NULL value, never deletes or drops anything;
--   * rows whose parent has no tenant (legacy NULL-tenant work orders) STAY NULL: they are listed
--     by the diagnostics (view diag_tenant_null) and fixed by the owner with the runbook SQL.
-- Deterministic and idempotent: a second run changes nothing.

-- 1. Top-level rows from their parents (same order as V4).
UPDATE centers c SET tenant_id = r.tenant_id
  FROM regions r
 WHERE c.region_id = r.id AND c.tenant_id IS NULL AND r.tenant_id IS NOT NULL;

UPDATE control_rooms x SET tenant_id = c.tenant_id
  FROM centers c
 WHERE x.center_id = c.id AND x.tenant_id IS NULL AND c.tenant_id IS NOT NULL;

UPDATE users u SET tenant_id = c.tenant_id
  FROM centers c
 WHERE u.center_id = c.id AND u.tenant_id IS NULL AND u.role::text <> 'system_admin'
   AND c.tenant_id IS NOT NULL;

UPDATE users u SET tenant_id = r.tenant_id
  FROM regions r
 WHERE u.region_id = r.id AND u.tenant_id IS NULL AND u.role::text <> 'system_admin'
   AND r.tenant_id IS NOT NULL;

UPDATE work_orders w SET tenant_id = c.tenant_id
  FROM centers c
 WHERE w.center_id = c.id AND w.tenant_id IS NULL AND c.tenant_id IS NOT NULL;

UPDATE departments d SET tenant_id = c.tenant_id
  FROM centers c
 WHERE d.center_id = c.id AND d.tenant_id IS NULL AND c.tenant_id IS NOT NULL;

UPDATE assets a SET tenant_id = c.tenant_id
  FROM centers c
 WHERE a.center_id = c.id AND a.tenant_id IS NULL AND c.tenant_id IS NOT NULL;

UPDATE inventory_items i SET tenant_id = c.tenant_id
  FROM centers c
 WHERE i.center_id = c.id AND i.tenant_id IS NULL AND c.tenant_id IS NOT NULL;

UPDATE inventory_movements m SET tenant_id = i.tenant_id
  FROM inventory_items i
 WHERE m.item_id = i.id AND m.tenant_id IS NULL AND i.tenant_id IS NOT NULL;

-- 2. Children of work orders / users (same rules as V5, NULL rows only).
UPDATE ticket_history t SET tenant_id = w.tenant_id
  FROM work_orders w
 WHERE t.work_order_id = w.id AND t.tenant_id IS NULL AND w.tenant_id IS NOT NULL;

UPDATE attachments t SET tenant_id = w.tenant_id
  FROM work_orders w
 WHERE t.work_order_id = w.id AND t.tenant_id IS NULL AND w.tenant_id IS NOT NULL;

-- Notifications: a NULL side (platform admin recipient, no work order) defers to the other side;
-- contradicting sides are left alone (reported by diag_tenant_mismatch).
UPDATE notifications n SET tenant_id = x.new_tenant
  FROM (
    SELECT n2.id AS nid, COALESCE(u.tenant_id, w.tenant_id) AS new_tenant
      FROM notifications n2
      JOIN users u ON u.id = n2.user_id
      LEFT JOIN work_orders w ON w.id = n2.work_order_id
     WHERE n2.tenant_id IS NULL
       AND COALESCE(u.tenant_id, w.tenant_id) IS NOT NULL
       AND (u.tenant_id IS NULL OR w.tenant_id IS NULL OR u.tenant_id = w.tenant_id)
  ) x
 WHERE n.id = x.nid AND n.tenant_id IS NULL;

-- 3. Re-attempt the constraints V6 deferred (copied logic; a no-op when already applied).
DO $$
DECLARE
  c record;
  nulls bigint;
  tenantless_wo bigint;
BEGIN
  SELECT count(*) INTO tenantless_wo FROM work_orders WHERE tenant_id IS NULL;

  FOR c IN SELECT * FROM (VALUES ('ticket_history'), ('attachments')) AS v(tbl) LOOP
    EXECUTE format('SELECT count(*) FROM %I WHERE tenant_id IS NULL', c.tbl) INTO nulls;
    IF EXISTS (SELECT 1 FROM pg_attribute
                WHERE attrelid = to_regclass('public.' || c.tbl) AND attname = 'tenant_id'
                  AND attnotnull) THEN
      NULL; -- already enforced
    ELSIF nulls = 0 AND tenantless_wo = 0 THEN
      EXECUTE format('ALTER TABLE %I ALTER COLUMN tenant_id SET NOT NULL', c.tbl);
      RAISE NOTICE 'V9: NOT NULL applied on %.tenant_id', c.tbl;
    ELSE
      RAISE WARNING 'V9: % has % NULL tenant rows and % tenant-less work orders exist; NOT NULL still deferred',
        c.tbl, nulls, tenantless_wo;
    END IF;
  END LOOP;

  IF tenantless_wo = 0 THEN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'notifications_tenant_when_work_order'
                      AND conrelid = to_regclass('public.notifications')) THEN
      ALTER TABLE notifications ADD CONSTRAINT notifications_tenant_when_work_order
        CHECK (tenant_id IS NOT NULL OR work_order_id IS NULL) NOT VALID;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
                WHERE conname = 'notifications_tenant_when_work_order'
                  AND conrelid = to_regclass('public.notifications') AND NOT convalidated) THEN
      BEGIN
        ALTER TABLE notifications VALIDATE CONSTRAINT notifications_tenant_when_work_order;
      EXCEPTION WHEN check_violation THEN
        RAISE WARNING 'V9: notifications_tenant_when_work_order stays NOT VALID (legacy rows violate it)';
      END;
    END IF;
  ELSE
    RAISE WARNING 'V9: % tenant-less work orders exist; notifications_tenant_when_work_order still deferred', tenantless_wo;
  END IF;
END $$;

-- 4. Report (counts only).
DO $$
DECLARE
  t text;
  n bigint;
BEGIN
  FOREACH t IN ARRAY ARRAY['regions','centers','control_rooms','work_orders','departments','assets',
                           'inventory_items','inventory_movements','maintenance_types',
                           'ticket_history','attachments','notifications'] LOOP
    EXECUTE format('SELECT count(*) FROM %I WHERE tenant_id IS NULL', t) INTO n;
    RAISE NOTICE 'V9 report: % rows in % have NULL tenant_id (left as is)', n, t;
  END LOOP;
  SELECT count(*) INTO n FROM users WHERE tenant_id IS NULL AND role::text <> 'system_admin' AND deleted_at IS NULL;
  RAISE NOTICE 'V9 report: % non-admin users have NULL tenant_id (assign via platform admin)', n;
END $$;
