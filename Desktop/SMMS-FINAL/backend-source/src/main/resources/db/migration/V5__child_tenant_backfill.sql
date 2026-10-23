-- V5: tenant_id on ticket_history, attachments, notifications, derived from verified parents.
-- Never a default tenant. Rows whose parent has no tenant are not touched.

ALTER TABLE ticket_history ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE attachments    ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE notifications  ADD COLUMN IF NOT EXISTS tenant_id UUID;

-- Also corrects rows that the old DataInitializer set to the default tenant.
UPDATE ticket_history t SET tenant_id = w.tenant_id
  FROM work_orders w
 WHERE t.work_order_id = w.id AND w.tenant_id IS NOT NULL
   AND t.tenant_id IS DISTINCT FROM w.tenant_id;

UPDATE attachments t SET tenant_id = w.tenant_id
  FROM work_orders w
 WHERE t.work_order_id = w.id AND w.tenant_id IS NOT NULL
   AND t.tenant_id IS DISTINCT FROM w.tenant_id;

-- Notifications: recipient tenant u, work order tenant w. Only when the result is non-NULL and
-- the two sides do not contradict each other (a NULL side, e.g. system_admin, defers to the other).
UPDATE notifications n SET tenant_id = x.new_tenant
  FROM (
    SELECT n2.id AS nid, COALESCE(u.tenant_id, w.tenant_id) AS new_tenant
      FROM notifications n2
      JOIN users u ON u.id = n2.user_id
      LEFT JOIN work_orders w ON w.id = n2.work_order_id
     WHERE COALESCE(u.tenant_id, w.tenant_id) IS NOT NULL
       AND (u.tenant_id IS NULL OR w.tenant_id IS NULL OR u.tenant_id = w.tenant_id)
  ) x
 WHERE n.id = x.nid AND n.tenant_id IS DISTINCT FROM x.new_tenant;

-- Verification report (counts only). Same queries live in m3-a-test/verify.sql.
DO $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM ticket_history WHERE tenant_id IS NULL;
  RAISE NOTICE 'V5 report: ticket_history NULL tenant = %', n;
  SELECT count(*) INTO n FROM ticket_history t JOIN work_orders w ON w.id = t.work_order_id
   WHERE w.tenant_id IS NOT NULL AND t.tenant_id <> w.tenant_id;
  RAISE NOTICE 'V5 report: ticket_history mismatch vs work order = %', n;

  SELECT count(*) INTO n FROM attachments WHERE tenant_id IS NULL;
  RAISE NOTICE 'V5 report: attachments NULL tenant = %', n;
  SELECT count(*) INTO n FROM attachments t JOIN work_orders w ON w.id = t.work_order_id
   WHERE w.tenant_id IS NOT NULL AND t.tenant_id <> w.tenant_id;
  RAISE NOTICE 'V5 report: attachments mismatch vs work order = %', n;

  SELECT count(*) INTO n FROM notifications WHERE tenant_id IS NULL;
  RAISE NOTICE 'V5 report: notifications NULL tenant = % (legitimate when no tenant on either side)', n;
  SELECT count(*) INTO n FROM notifications x
    JOIN users u ON u.id = x.user_id
    JOIN work_orders w ON w.id = x.work_order_id
   WHERE u.tenant_id IS NOT NULL AND w.tenant_id IS NOT NULL AND u.tenant_id <> w.tenant_id;
  RAISE NOTICE 'V5 report: notifications CONFLICT (recipient tenant <> work order tenant, untouched) = %', n;
END $$;
