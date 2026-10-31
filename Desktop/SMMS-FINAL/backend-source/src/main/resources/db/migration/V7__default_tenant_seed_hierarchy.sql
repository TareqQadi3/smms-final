-- V7: give the init.sql seed hierarchy (fixed ids) the default tenant, deterministically.
-- Replaces the blind startup backfill removed from DataInitializer. Touches only the three
-- fixed seed ids and only rows whose tenant_id is still NULL; no other row is ever defaulted.
-- Creates the default tenant (same slug/name DataInitializer uses) only if neither the slug
-- nor the name is taken, so existing installs are unaffected.
INSERT INTO tenants (name, name_ar, slug, is_active)
SELECT 'Jazan Health Cluster', 'تجمع جازان الصحي', 'default', TRUE
 WHERE NOT EXISTS (SELECT 1 FROM tenants WHERE slug = 'default')
   AND NOT EXISTS (SELECT 1 FROM tenants WHERE name = 'Jazan Health Cluster');

UPDATE regions SET tenant_id = (SELECT id FROM tenants WHERE slug = 'default')
 WHERE id = '11111111-1111-1111-1111-111111111111' AND tenant_id IS NULL
   AND EXISTS (SELECT 1 FROM tenants WHERE slug = 'default');

UPDATE centers c SET tenant_id = r.tenant_id FROM regions r
 WHERE c.id = '22222222-2222-2222-2222-222222222222' AND c.region_id = r.id
   AND c.tenant_id IS NULL AND r.tenant_id IS NOT NULL;

UPDATE control_rooms x SET tenant_id = c.tenant_id FROM centers c
 WHERE x.center_id = c.id AND c.id = '22222222-2222-2222-2222-222222222222'
   AND x.tenant_id IS NULL AND c.tenant_id IS NOT NULL;
