-- FP1 W2a / V30: warehouses (stock locations of a center).
-- Additive. Tenant is required. The only data step is deterministic: a MAIN warehouse for every
-- center whose legacy inventory_items hold a positive quantity (balances follow in V32/V33).
CREATE TABLE IF NOT EXISTS warehouses (
  id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id  UUID NOT NULL REFERENCES tenants(id),
  center_id  UUID NOT NULL REFERENCES centers(id),
  code       VARCHAR(40)  NOT NULL,
  name       VARCHAR(200) NOT NULL,
  name_ar    VARCHAR(200) NOT NULL,
  is_active  BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  deleted_at TIMESTAMPTZ,
  CONSTRAINT uq_warehouses_center_code UNIQUE (center_id, code),
  CONSTRAINT uq_warehouses_id_tenant UNIQUE (id, tenant_id)
);
CREATE INDEX IF NOT EXISTS idx_warehouses_tenant_center ON warehouses(tenant_id, center_id);

DROP TRIGGER IF EXISTS trg_warehouses_updated ON warehouses;
CREATE TRIGGER trg_warehouses_updated BEFORE UPDATE ON warehouses
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- composite key target so child tables can pin (parent, tenant) together
CREATE UNIQUE INDEX IF NOT EXISTS uq_inventory_items_id_tenant ON inventory_items(id, tenant_id);

DO $$
DECLARE created INT; skipped INT;
BEGIN
  INSERT INTO warehouses (tenant_id, center_id, code, name, name_ar)
  SELECT DISTINCT c.tenant_id, c.id, 'MAIN', 'Main warehouse', 'المستودع الرئيسي'
    FROM inventory_items i JOIN centers c ON c.id = i.center_id
   WHERE i.quantity > 0 AND c.tenant_id IS NOT NULL AND i.tenant_id = c.tenant_id AND i.deleted_at IS NULL
  ON CONFLICT (center_id, code) DO NOTHING;
  GET DIAGNOSTICS created = ROW_COUNT;
  SELECT count(*) INTO skipped FROM inventory_items i LEFT JOIN centers c ON c.id = i.center_id
   WHERE i.quantity <> 0 AND (c.tenant_id IS NULL OR i.tenant_id IS DISTINCT FROM c.tenant_id OR i.quantity < 0);
  RAISE NOTICE 'V30: % MAIN warehouse(s) created; % legacy item(s) with unusable quantity skipped', created, skipped;
END $$;
