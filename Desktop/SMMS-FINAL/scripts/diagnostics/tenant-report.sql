-- READ-ONLY wrong-tenant and NULL-tenant report (ids only). Requires migration V10. Run with:
--   docker exec -i smms_postgres psql -U "$DB_USER" -d "$DB_NAME" < scripts/diagnostics/tenant-report.sql
-- Nothing here changes data. Fixes are manual, per row, by the owner, after a fresh backup
-- (see the runbook).
\echo '== counts per check (rows with n = 0 are not shown)'
SELECT * FROM diag_tenant_summary() WHERE n > 0 ORDER BY check_name;

\echo '== wrong-tenant rows (both tenants set and different, or parent tenant-less)'
SELECT check_name, table_name, row_id, row_tenant, expected_tenant, ref_table, ref_id
  FROM diag_tenant_mismatch ORDER BY check_name, row_id LIMIT 500;

\echo '== rows without a tenant (first 200 per table; notifications without a work order are normal)'
SELECT table_name, row_id FROM (
  SELECT table_name, row_id, row_number() OVER (PARTITION BY table_name ORDER BY row_id) AS rn
    FROM diag_tenant_null) x
 WHERE rn <= 200 ORDER BY table_name, row_id;
