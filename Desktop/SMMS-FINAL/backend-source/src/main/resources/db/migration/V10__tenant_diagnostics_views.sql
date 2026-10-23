-- V10: read-only tenant diagnostics (views + one STABLE function). No UPDATE/DELETE/DDL on data.
-- Nothing here changes rows: wrong-tenant rows are REPORTED for the owner to review per row.
-- Explicit column lists on purpose (no SELECT *): later migrations add columns to these tables.
-- A row is reported when both tenants are non-NULL and differ (check <name>), or when the child
-- has a tenant but the parent has none (check <name>_parent_tenantless).
-- Not covered (no schema link exists): maintenance_types is not referenced by work_orders;
-- tenant_subscriptions.tenant_id is NOT NULL with a FK, so it cannot be tenant-less.

CREATE OR REPLACE VIEW diag_tenant_mismatch AS
  SELECT 'centers_region'::text AS check_name, 'centers'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'regions'::text AS ref_table, p.id AS ref_id
    FROM centers c JOIN regions p ON p.id = c.region_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'control_rooms_center'::text AS check_name, 'control_rooms'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM control_rooms c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'users_center'::text AS check_name, 'users'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM users c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id AND c.role::text <> 'system_admin'
  UNION ALL
  SELECT 'users_region'::text AS check_name, 'users'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'regions'::text AS ref_table, p.id AS ref_id
    FROM users c JOIN regions p ON p.id = c.region_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id AND c.role::text <> 'system_admin'
  UNION ALL
  SELECT 'work_orders_center'::text AS check_name, 'work_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM work_orders c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'work_orders_control_room'::text AS check_name, 'work_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'control_rooms'::text AS ref_table, p.id AS ref_id
    FROM work_orders c JOIN control_rooms p ON p.id = c.control_room_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'work_orders_requested_by'::text AS check_name, 'work_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM work_orders c JOIN users p ON p.id = c.requested_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'work_orders_assigned_to'::text AS check_name, 'work_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM work_orders c JOIN users p ON p.id = c.assigned_to_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'departments_center'::text AS check_name, 'departments'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM departments c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'assets_center'::text AS check_name, 'assets'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM assets c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'assets_department'::text AS check_name, 'assets'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'departments'::text AS ref_table, p.id AS ref_id
    FROM assets c JOIN departments p ON p.id = c.department_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'inventory_items_center'::text AS check_name, 'inventory_items'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM inventory_items c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'inventory_movements_item'::text AS check_name, 'inventory_movements'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'inventory_items'::text AS ref_table, p.id AS ref_id
    FROM inventory_movements c JOIN inventory_items p ON p.id = c.item_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'inventory_movements_work_order'::text AS check_name, 'inventory_movements'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM inventory_movements c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'ticket_history_work_order'::text AS check_name, 'ticket_history'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM ticket_history c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'attachments_work_order'::text AS check_name, 'attachments'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM attachments c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'notifications_user'::text AS check_name, 'notifications'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM notifications c JOIN users p ON p.id = c.user_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'notifications_work_order'::text AS check_name, 'notifications'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM notifications c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'centers_region_parent_tenantless'::text AS check_name, 'centers'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'regions'::text AS ref_table, p.id AS ref_id
    FROM centers c JOIN regions p ON p.id = c.region_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'control_rooms_center_parent_tenantless'::text AS check_name, 'control_rooms'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM control_rooms c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'users_center_parent_tenantless'::text AS check_name, 'users'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM users c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND c.role::text <> 'system_admin'
  UNION ALL
  SELECT 'users_region_parent_tenantless'::text AS check_name, 'users'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'regions'::text AS ref_table, p.id AS ref_id
    FROM users c JOIN regions p ON p.id = c.region_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND c.role::text <> 'system_admin'
  UNION ALL
  SELECT 'work_orders_center_parent_tenantless'::text AS check_name, 'work_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM work_orders c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'work_orders_control_room_parent_tenantless'::text AS check_name, 'work_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'control_rooms'::text AS ref_table, p.id AS ref_id
    FROM work_orders c JOIN control_rooms p ON p.id = c.control_room_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'work_orders_requested_by_parent_tenantless'::text AS check_name, 'work_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM work_orders c JOIN users p ON p.id = c.requested_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'work_orders_assigned_to_parent_tenantless'::text AS check_name, 'work_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM work_orders c JOIN users p ON p.id = c.assigned_to_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'departments_center_parent_tenantless'::text AS check_name, 'departments'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM departments c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'assets_center_parent_tenantless'::text AS check_name, 'assets'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM assets c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'assets_department_parent_tenantless'::text AS check_name, 'assets'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'departments'::text AS ref_table, p.id AS ref_id
    FROM assets c JOIN departments p ON p.id = c.department_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'inventory_items_center_parent_tenantless'::text AS check_name, 'inventory_items'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM inventory_items c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'inventory_movements_item_parent_tenantless'::text AS check_name, 'inventory_movements'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'inventory_items'::text AS ref_table, p.id AS ref_id
    FROM inventory_movements c JOIN inventory_items p ON p.id = c.item_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'inventory_movements_work_order_parent_tenantless'::text AS check_name, 'inventory_movements'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM inventory_movements c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'ticket_history_work_order_parent_tenantless'::text AS check_name, 'ticket_history'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM ticket_history c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'attachments_work_order_parent_tenantless'::text AS check_name, 'attachments'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM attachments c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'notifications_user_parent_tenantless'::text AS check_name, 'notifications'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM notifications c JOIN users p ON p.id = c.user_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'notifications_work_order_parent_tenantless'::text AS check_name, 'notifications'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM notifications c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'work_orders_center_vs_room_center'::text, 'work_orders'::text, w.id,
         w.tenant_id, cr.tenant_id, 'control_rooms'::text, cr.id
    FROM work_orders w JOIN control_rooms cr ON cr.id = w.control_room_id
   WHERE w.center_id <> cr.center_id
  UNION ALL
  SELECT 'user_room_access_user_vs_room'::text, 'user_control_room_access'::text, a.id,
         u.tenant_id, cr.tenant_id, 'control_rooms'::text, cr.id
    FROM user_control_room_access a
    JOIN users u ON u.id = a.user_id JOIN control_rooms cr ON cr.id = a.control_room_id
   WHERE u.tenant_id IS NOT NULL AND cr.tenant_id IS NOT NULL AND u.tenant_id <> cr.tenant_id
  UNION ALL
  SELECT 'user_room_access_room_tenantless'::text, 'user_control_room_access'::text, a.id,
         u.tenant_id, cr.tenant_id, 'control_rooms'::text, cr.id
    FROM user_control_room_access a
    JOIN users u ON u.id = a.user_id JOIN control_rooms cr ON cr.id = a.control_room_id
   WHERE u.tenant_id IS NOT NULL AND cr.tenant_id IS NULL
  UNION ALL
  SELECT 'system_admin_with_tenant'::text, 'users'::text, u.id,
         u.tenant_id, NULL::uuid, NULL::text, NULL::uuid
    FROM users u
   WHERE u.role::text = 'system_admin' AND u.tenant_id IS NOT NULL;

CREATE OR REPLACE VIEW diag_tenant_null AS
  SELECT 'regions'::text AS table_name, id AS row_id FROM regions WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'centers'::text AS table_name, id AS row_id FROM centers WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'control_rooms'::text AS table_name, id AS row_id FROM control_rooms WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'work_orders'::text AS table_name, id AS row_id FROM work_orders WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'maintenance_types'::text AS table_name, id AS row_id FROM maintenance_types WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'departments'::text AS table_name, id AS row_id FROM departments WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'assets'::text AS table_name, id AS row_id FROM assets WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'inventory_items'::text AS table_name, id AS row_id FROM inventory_items WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'inventory_movements'::text AS table_name, id AS row_id FROM inventory_movements WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'ticket_history'::text AS table_name, id AS row_id FROM ticket_history WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'attachments'::text AS table_name, id AS row_id FROM attachments WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'notifications'::text AS table_name, id AS row_id FROM notifications WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'users'::text AS table_name, id AS row_id FROM users WHERE tenant_id IS NULL AND role::text <> 'system_admin' AND deleted_at IS NULL;

CREATE OR REPLACE FUNCTION diag_tenant_summary()
RETURNS TABLE (check_name text, n bigint)
LANGUAGE sql STABLE AS $$
  SELECT m.check_name, count(*)::bigint FROM diag_tenant_mismatch m GROUP BY m.check_name
  UNION ALL
  SELECT 'null:' || z.table_name, count(*)::bigint FROM diag_tenant_null z GROUP BY z.table_name
$$;
