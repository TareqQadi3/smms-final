-- READ-ONLY. Flyway state and deferred migration items. Run with:
--   docker exec -i smms_postgres psql -U "$DB_USER" -d "$DB_NAME" < scripts/diagnostics/flyway-status.sql
-- Shows no row data, only versions, constraint names and counts.
\echo '== flyway_schema_history (a version missing here after a failed start = rolled back, safe to retry)'
SELECT installed_rank, version, description, success, installed_on
  FROM flyway_schema_history ORDER BY installed_rank;

\echo '== failed rows (must be 0 rows)'
SELECT installed_rank, version, description FROM flyway_schema_history WHERE NOT success;

\echo '== constraints that are still NOT VALID (legacy rows violate them)'
SELECT conrelid::regclass AS table_name, conname
  FROM pg_constraint WHERE NOT convalidated AND connamespace = 'public'::regnamespace ORDER BY 1, 2;

\echo '== deferred items'
SELECT c.relname AS table_name, a.attname AS column_name, a.attnotnull AS not_null
  FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
 WHERE c.relname IN ('ticket_history', 'attachments') AND a.attname = 'tenant_id';
SELECT 'notifications_tenant_when_work_order' AS constraint_name,
       EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'notifications_tenant_when_work_order') AS present;
SELECT 'uq_users_username_lower' AS index_name,
       EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'uq_users_username_lower') AS present;
