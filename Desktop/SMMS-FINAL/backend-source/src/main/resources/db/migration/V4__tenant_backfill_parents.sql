-- V4: derive tenant_id from VERIFIED parents only (replaces DataInitializer parent derivation
-- and its default-tenant fallbacks). Every statement only fills NULLs and only when the parent
-- has a tenant. Rows that cannot be derived stay NULL and are reported by the notice block below.
-- Regions and maintenance_types have no parent: never defaulted.

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

-- Existing behaviour: platform admins are never bound to a tenant.
UPDATE users SET tenant_id = NULL WHERE role::text = 'system_admin' AND tenant_id IS NOT NULL;

-- Report what could not be derived (counts only, no data).
DO $$
DECLARE
  t text;
  n bigint;
BEGIN
  FOREACH t IN ARRAY ARRAY['regions','centers','control_rooms','work_orders','departments','assets',
                           'inventory_items','inventory_movements','maintenance_types'] LOOP
    EXECUTE format('SELECT count(*) FROM %I WHERE tenant_id IS NULL', t) INTO n;
    RAISE NOTICE 'V4 report: % rows in % still have NULL tenant_id (left as is, assign manually)', n, t;
  END LOOP;
  SELECT count(*) INTO n FROM users WHERE tenant_id IS NULL AND role::text <> 'system_admin';
  RAISE NOTICE 'V4 report: % non-admin users have NULL tenant_id (cannot log in until assigned)', n;
END $$;
