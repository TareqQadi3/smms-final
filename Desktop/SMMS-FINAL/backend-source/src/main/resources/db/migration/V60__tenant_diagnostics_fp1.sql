-- V60: tenant diagnostics for the feature-program-1 tables (work-order extras, inventory ledger,
-- procurement, material requests, maintenance plans, asset downtime, notification delivery).
-- Re-creates the V10 views and function with the V10 checks unchanged plus the new ones, in the same
-- style. READ-ONLY: views and one STABLE function, no DML on data. Explicit column lists (no SELECT *).
-- Every new table has tenant_id NOT NULL with a FK to tenants, so the *_null entries are a tripwire
-- (they stay empty); the checks that matter compare the row tenant with the tenant of each parent row.
-- idempotency_records.tenant_id is nullable by design (NULL = platform admin), so only a real
-- mismatch with the owning user is reported. Mismatches are REPORTED for the owner to review per row.

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
  SELECT 'work_order_info_requests_work_order'::text AS check_name, 'work_order_info_requests'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM work_order_info_requests c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'work_order_info_requests_requested_by'::text AS check_name, 'work_order_info_requests'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM work_order_info_requests c JOIN users p ON p.id = c.requested_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'work_order_info_requests_answered_by'::text AS check_name, 'work_order_info_requests'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM work_order_info_requests c JOIN users p ON p.id = c.answered_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'work_order_escalations_work_order'::text AS check_name, 'work_order_escalations'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM work_order_escalations c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'work_order_escalations_triggered_by'::text AS check_name, 'work_order_escalations'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM work_order_escalations c JOIN users p ON p.id = c.triggered_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'work_order_ratings_work_order'::text AS check_name, 'work_order_ratings'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM work_order_ratings c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'work_order_ratings_rated_by'::text AS check_name, 'work_order_ratings'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM work_order_ratings c JOIN users p ON p.id = c.rated_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'work_order_status_links_work_order'::text AS check_name, 'work_order_status_links'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM work_order_status_links c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'work_order_status_links_created_by'::text AS check_name, 'work_order_status_links'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM work_order_status_links c JOIN users p ON p.id = c.created_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'warehouses_center'::text AS check_name, 'warehouses'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM warehouses c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'inventory_items_uom'::text AS check_name, 'inventory_items'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'units_of_measure'::text AS ref_table, p.id AS ref_id
    FROM inventory_items c JOIN units_of_measure p ON p.id = c.uom_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'stock_balances_item'::text AS check_name, 'stock_balances'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'inventory_items'::text AS ref_table, p.id AS ref_id
    FROM stock_balances c JOIN inventory_items p ON p.id = c.item_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'stock_balances_warehouse'::text AS check_name, 'stock_balances'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'warehouses'::text AS ref_table, p.id AS ref_id
    FROM stock_balances c JOIN warehouses p ON p.id = c.warehouse_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'inventory_movements_warehouse'::text AS check_name, 'inventory_movements'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'warehouses'::text AS ref_table, p.id AS ref_id
    FROM inventory_movements c JOIN warehouses p ON p.id = c.warehouse_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'purchase_orders_center'::text AS check_name, 'purchase_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM purchase_orders c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'purchase_orders_supplier'::text AS check_name, 'purchase_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'suppliers'::text AS ref_table, p.id AS ref_id
    FROM purchase_orders c JOIN suppliers p ON p.id = c.supplier_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'purchase_orders_warehouse'::text AS check_name, 'purchase_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'warehouses'::text AS ref_table, p.id AS ref_id
    FROM purchase_orders c JOIN warehouses p ON p.id = c.warehouse_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'purchase_orders_work_order'::text AS check_name, 'purchase_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM purchase_orders c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'purchase_orders_created_by'::text AS check_name, 'purchase_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM purchase_orders c JOIN users p ON p.id = c.created_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'purchase_orders_approved_by'::text AS check_name, 'purchase_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM purchase_orders c JOIN users p ON p.id = c.approved_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'purchase_orders_material_request'::text AS check_name, 'purchase_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'material_requests'::text AS ref_table, p.id AS ref_id
    FROM purchase_orders c JOIN material_requests p ON p.id = c.material_request_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'purchase_order_lines_po'::text AS check_name, 'purchase_order_lines'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'purchase_orders'::text AS ref_table, p.id AS ref_id
    FROM purchase_order_lines c JOIN purchase_orders p ON p.id = c.po_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'purchase_order_lines_item'::text AS check_name, 'purchase_order_lines'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'inventory_items'::text AS ref_table, p.id AS ref_id
    FROM purchase_order_lines c JOIN inventory_items p ON p.id = c.item_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'po_receipts_po'::text AS check_name, 'po_receipts'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'purchase_orders'::text AS ref_table, p.id AS ref_id
    FROM po_receipts c JOIN purchase_orders p ON p.id = c.po_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'po_receipts_warehouse'::text AS check_name, 'po_receipts'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'warehouses'::text AS ref_table, p.id AS ref_id
    FROM po_receipts c JOIN warehouses p ON p.id = c.warehouse_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'po_receipts_received_by'::text AS check_name, 'po_receipts'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM po_receipts c JOIN users p ON p.id = c.received_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'po_receipt_lines_receipt'::text AS check_name, 'po_receipt_lines'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'po_receipts'::text AS ref_table, p.id AS ref_id
    FROM po_receipt_lines c JOIN po_receipts p ON p.id = c.receipt_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'po_receipt_lines_po_line'::text AS check_name, 'po_receipt_lines'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'purchase_order_lines'::text AS ref_table, p.id AS ref_id
    FROM po_receipt_lines c JOIN purchase_order_lines p ON p.id = c.po_line_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'material_requests_center'::text AS check_name, 'material_requests'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM material_requests c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'material_requests_work_order'::text AS check_name, 'material_requests'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM material_requests c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'material_requests_warehouse'::text AS check_name, 'material_requests'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'warehouses'::text AS ref_table, p.id AS ref_id
    FROM material_requests c JOIN warehouses p ON p.id = c.warehouse_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'material_requests_requested_by'::text AS check_name, 'material_requests'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM material_requests c JOIN users p ON p.id = c.requested_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'material_requests_decided_by'::text AS check_name, 'material_requests'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM material_requests c JOIN users p ON p.id = c.decided_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'material_request_lines_mr'::text AS check_name, 'material_request_lines'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'material_requests'::text AS ref_table, p.id AS ref_id
    FROM material_request_lines c JOIN material_requests p ON p.id = c.mr_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'material_request_lines_item'::text AS check_name, 'material_request_lines'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'inventory_items'::text AS ref_table, p.id AS ref_id
    FROM material_request_lines c JOIN inventory_items p ON p.id = c.item_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'maintenance_plans_center'::text AS check_name, 'maintenance_plans'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM maintenance_plans c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'maintenance_plans_asset'::text AS check_name, 'maintenance_plans'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'assets'::text AS ref_table, p.id AS ref_id
    FROM maintenance_plans c JOIN assets p ON p.id = c.asset_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'maintenance_plans_control_room'::text AS check_name, 'maintenance_plans'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'control_rooms'::text AS ref_table, p.id AS ref_id
    FROM maintenance_plans c JOIN control_rooms p ON p.id = c.control_room_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'maintenance_plans_owner'::text AS check_name, 'maintenance_plans'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM maintenance_plans c JOIN users p ON p.id = c.owner_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'maintenance_plan_runs_plan'::text AS check_name, 'maintenance_plan_runs'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'maintenance_plans'::text AS ref_table, p.id AS ref_id
    FROM maintenance_plan_runs c JOIN maintenance_plans p ON p.id = c.plan_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'maintenance_plan_runs_work_order'::text AS check_name, 'maintenance_plan_runs'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM maintenance_plan_runs c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'asset_downtime_asset'::text AS check_name, 'asset_downtime'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'assets'::text AS ref_table, p.id AS ref_id
    FROM asset_downtime c JOIN assets p ON p.id = c.asset_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'asset_downtime_work_order'::text AS check_name, 'asset_downtime'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM asset_downtime c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'asset_downtime_recorded_by'::text AS check_name, 'asset_downtime'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM asset_downtime c JOIN users p ON p.id = c.recorded_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'asset_downtime_ended_by'::text AS check_name, 'asset_downtime'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM asset_downtime c JOIN users p ON p.id = c.ended_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'notification_preferences_user'::text AS check_name, 'notification_preferences'::text AS table_name, c.user_id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM notification_preferences c JOIN users p ON p.id = c.user_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'notification_deliveries_notification'::text AS check_name, 'notification_deliveries'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'notifications'::text AS ref_table, p.id AS ref_id
    FROM notification_deliveries c JOIN notifications p ON p.id = c.notification_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'notification_deliveries_user'::text AS check_name, 'notification_deliveries'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM notification_deliveries c JOIN users p ON p.id = c.user_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
  UNION ALL
  SELECT 'work_order_info_requests_work_order_parent_tenantless'::text AS check_name, 'work_order_info_requests'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM work_order_info_requests c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'work_order_info_requests_requested_by_parent_tenantless'::text AS check_name, 'work_order_info_requests'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM work_order_info_requests c JOIN users p ON p.id = c.requested_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'work_order_info_requests_answered_by_parent_tenantless'::text AS check_name, 'work_order_info_requests'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM work_order_info_requests c JOIN users p ON p.id = c.answered_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'work_order_escalations_work_order_parent_tenantless'::text AS check_name, 'work_order_escalations'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM work_order_escalations c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'work_order_escalations_triggered_by_parent_tenantless'::text AS check_name, 'work_order_escalations'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM work_order_escalations c JOIN users p ON p.id = c.triggered_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'work_order_ratings_work_order_parent_tenantless'::text AS check_name, 'work_order_ratings'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM work_order_ratings c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'work_order_ratings_rated_by_parent_tenantless'::text AS check_name, 'work_order_ratings'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM work_order_ratings c JOIN users p ON p.id = c.rated_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'work_order_status_links_work_order_parent_tenantless'::text AS check_name, 'work_order_status_links'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM work_order_status_links c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'work_order_status_links_created_by_parent_tenantless'::text AS check_name, 'work_order_status_links'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM work_order_status_links c JOIN users p ON p.id = c.created_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'warehouses_center_parent_tenantless'::text AS check_name, 'warehouses'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM warehouses c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'inventory_items_uom_parent_tenantless'::text AS check_name, 'inventory_items'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'units_of_measure'::text AS ref_table, p.id AS ref_id
    FROM inventory_items c JOIN units_of_measure p ON p.id = c.uom_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'stock_balances_item_parent_tenantless'::text AS check_name, 'stock_balances'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'inventory_items'::text AS ref_table, p.id AS ref_id
    FROM stock_balances c JOIN inventory_items p ON p.id = c.item_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'stock_balances_warehouse_parent_tenantless'::text AS check_name, 'stock_balances'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'warehouses'::text AS ref_table, p.id AS ref_id
    FROM stock_balances c JOIN warehouses p ON p.id = c.warehouse_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'inventory_movements_warehouse_parent_tenantless'::text AS check_name, 'inventory_movements'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'warehouses'::text AS ref_table, p.id AS ref_id
    FROM inventory_movements c JOIN warehouses p ON p.id = c.warehouse_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'purchase_orders_center_parent_tenantless'::text AS check_name, 'purchase_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM purchase_orders c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'purchase_orders_supplier_parent_tenantless'::text AS check_name, 'purchase_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'suppliers'::text AS ref_table, p.id AS ref_id
    FROM purchase_orders c JOIN suppliers p ON p.id = c.supplier_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'purchase_orders_warehouse_parent_tenantless'::text AS check_name, 'purchase_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'warehouses'::text AS ref_table, p.id AS ref_id
    FROM purchase_orders c JOIN warehouses p ON p.id = c.warehouse_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'purchase_orders_work_order_parent_tenantless'::text AS check_name, 'purchase_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM purchase_orders c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'purchase_orders_created_by_parent_tenantless'::text AS check_name, 'purchase_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM purchase_orders c JOIN users p ON p.id = c.created_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'purchase_orders_approved_by_parent_tenantless'::text AS check_name, 'purchase_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM purchase_orders c JOIN users p ON p.id = c.approved_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'purchase_orders_material_request_parent_tenantless'::text AS check_name, 'purchase_orders'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'material_requests'::text AS ref_table, p.id AS ref_id
    FROM purchase_orders c JOIN material_requests p ON p.id = c.material_request_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'purchase_order_lines_po_parent_tenantless'::text AS check_name, 'purchase_order_lines'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'purchase_orders'::text AS ref_table, p.id AS ref_id
    FROM purchase_order_lines c JOIN purchase_orders p ON p.id = c.po_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'purchase_order_lines_item_parent_tenantless'::text AS check_name, 'purchase_order_lines'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'inventory_items'::text AS ref_table, p.id AS ref_id
    FROM purchase_order_lines c JOIN inventory_items p ON p.id = c.item_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'po_receipts_po_parent_tenantless'::text AS check_name, 'po_receipts'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'purchase_orders'::text AS ref_table, p.id AS ref_id
    FROM po_receipts c JOIN purchase_orders p ON p.id = c.po_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'po_receipts_warehouse_parent_tenantless'::text AS check_name, 'po_receipts'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'warehouses'::text AS ref_table, p.id AS ref_id
    FROM po_receipts c JOIN warehouses p ON p.id = c.warehouse_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'po_receipts_received_by_parent_tenantless'::text AS check_name, 'po_receipts'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM po_receipts c JOIN users p ON p.id = c.received_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'po_receipt_lines_receipt_parent_tenantless'::text AS check_name, 'po_receipt_lines'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'po_receipts'::text AS ref_table, p.id AS ref_id
    FROM po_receipt_lines c JOIN po_receipts p ON p.id = c.receipt_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'po_receipt_lines_po_line_parent_tenantless'::text AS check_name, 'po_receipt_lines'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'purchase_order_lines'::text AS ref_table, p.id AS ref_id
    FROM po_receipt_lines c JOIN purchase_order_lines p ON p.id = c.po_line_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'material_requests_center_parent_tenantless'::text AS check_name, 'material_requests'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM material_requests c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'material_requests_work_order_parent_tenantless'::text AS check_name, 'material_requests'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM material_requests c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'material_requests_warehouse_parent_tenantless'::text AS check_name, 'material_requests'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'warehouses'::text AS ref_table, p.id AS ref_id
    FROM material_requests c JOIN warehouses p ON p.id = c.warehouse_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'material_requests_requested_by_parent_tenantless'::text AS check_name, 'material_requests'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM material_requests c JOIN users p ON p.id = c.requested_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'material_requests_decided_by_parent_tenantless'::text AS check_name, 'material_requests'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM material_requests c JOIN users p ON p.id = c.decided_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'material_request_lines_mr_parent_tenantless'::text AS check_name, 'material_request_lines'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'material_requests'::text AS ref_table, p.id AS ref_id
    FROM material_request_lines c JOIN material_requests p ON p.id = c.mr_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'material_request_lines_item_parent_tenantless'::text AS check_name, 'material_request_lines'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'inventory_items'::text AS ref_table, p.id AS ref_id
    FROM material_request_lines c JOIN inventory_items p ON p.id = c.item_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'maintenance_plans_center_parent_tenantless'::text AS check_name, 'maintenance_plans'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'centers'::text AS ref_table, p.id AS ref_id
    FROM maintenance_plans c JOIN centers p ON p.id = c.center_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'maintenance_plans_asset_parent_tenantless'::text AS check_name, 'maintenance_plans'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'assets'::text AS ref_table, p.id AS ref_id
    FROM maintenance_plans c JOIN assets p ON p.id = c.asset_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'maintenance_plans_control_room_parent_tenantless'::text AS check_name, 'maintenance_plans'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'control_rooms'::text AS ref_table, p.id AS ref_id
    FROM maintenance_plans c JOIN control_rooms p ON p.id = c.control_room_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'maintenance_plans_owner_parent_tenantless'::text AS check_name, 'maintenance_plans'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM maintenance_plans c JOIN users p ON p.id = c.owner_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'maintenance_plan_runs_plan_parent_tenantless'::text AS check_name, 'maintenance_plan_runs'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'maintenance_plans'::text AS ref_table, p.id AS ref_id
    FROM maintenance_plan_runs c JOIN maintenance_plans p ON p.id = c.plan_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'maintenance_plan_runs_work_order_parent_tenantless'::text AS check_name, 'maintenance_plan_runs'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM maintenance_plan_runs c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'asset_downtime_asset_parent_tenantless'::text AS check_name, 'asset_downtime'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'assets'::text AS ref_table, p.id AS ref_id
    FROM asset_downtime c JOIN assets p ON p.id = c.asset_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'asset_downtime_work_order_parent_tenantless'::text AS check_name, 'asset_downtime'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'work_orders'::text AS ref_table, p.id AS ref_id
    FROM asset_downtime c JOIN work_orders p ON p.id = c.work_order_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'asset_downtime_recorded_by_parent_tenantless'::text AS check_name, 'asset_downtime'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM asset_downtime c JOIN users p ON p.id = c.recorded_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'asset_downtime_ended_by_parent_tenantless'::text AS check_name, 'asset_downtime'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM asset_downtime c JOIN users p ON p.id = c.ended_by_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'notification_preferences_user_parent_tenantless'::text AS check_name, 'notification_preferences'::text AS table_name, c.user_id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM notification_preferences c JOIN users p ON p.id = c.user_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'notification_deliveries_notification_parent_tenantless'::text AS check_name, 'notification_deliveries'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'notifications'::text AS ref_table, p.id AS ref_id
    FROM notification_deliveries c JOIN notifications p ON p.id = c.notification_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL
  UNION ALL
  SELECT 'notification_deliveries_user_parent_tenantless'::text AS check_name, 'notification_deliveries'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM notification_deliveries c JOIN users p ON p.id = c.user_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NULL AND p.role::text <> 'system_admin'
  UNION ALL
  SELECT 'idempotency_records_user'::text AS check_name, 'idempotency_records'::text AS table_name, c.id AS row_id,
         c.tenant_id AS row_tenant, p.tenant_id AS expected_tenant,
         'users'::text AS ref_table, p.id AS ref_id
    FROM idempotency_records c JOIN users p ON p.id = c.user_id
   WHERE c.tenant_id IS NOT NULL AND p.tenant_id IS NOT NULL AND c.tenant_id <> p.tenant_id
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
  SELECT 'users'::text AS table_name, id AS row_id FROM users WHERE tenant_id IS NULL AND role::text <> 'system_admin' AND deleted_at IS NULL
  UNION ALL
  SELECT 'work_order_info_requests'::text AS table_name, id AS row_id FROM work_order_info_requests WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'work_order_escalations'::text AS table_name, id AS row_id FROM work_order_escalations WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'work_order_ratings'::text AS table_name, id AS row_id FROM work_order_ratings WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'work_order_status_links'::text AS table_name, id AS row_id FROM work_order_status_links WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'warehouses'::text AS table_name, id AS row_id FROM warehouses WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'units_of_measure'::text AS table_name, id AS row_id FROM units_of_measure WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'stock_balances'::text AS table_name, id AS row_id FROM stock_balances WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'suppliers'::text AS table_name, id AS row_id FROM suppliers WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'purchase_orders'::text AS table_name, id AS row_id FROM purchase_orders WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'purchase_order_lines'::text AS table_name, id AS row_id FROM purchase_order_lines WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'po_receipts'::text AS table_name, id AS row_id FROM po_receipts WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'po_receipt_lines'::text AS table_name, id AS row_id FROM po_receipt_lines WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'material_requests'::text AS table_name, id AS row_id FROM material_requests WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'material_request_lines'::text AS table_name, id AS row_id FROM material_request_lines WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'maintenance_plans'::text AS table_name, id AS row_id FROM maintenance_plans WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'maintenance_plan_runs'::text AS table_name, id AS row_id FROM maintenance_plan_runs WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'asset_downtime'::text AS table_name, id AS row_id FROM asset_downtime WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'notification_preferences'::text AS table_name, user_id AS row_id FROM notification_preferences WHERE tenant_id IS NULL
  UNION ALL
  SELECT 'notification_deliveries'::text AS table_name, id AS row_id FROM notification_deliveries WHERE tenant_id IS NULL;

CREATE OR REPLACE FUNCTION diag_tenant_summary()
RETURNS TABLE (check_name text, n bigint)
LANGUAGE sql STABLE AS $$
  SELECT m.check_name, count(*)::bigint FROM diag_tenant_mismatch m GROUP BY m.check_name
  UNION ALL
  SELECT 'null:' || z.table_name, count(*)::bigint FROM diag_tenant_null z GROUP BY z.table_name
$$;
