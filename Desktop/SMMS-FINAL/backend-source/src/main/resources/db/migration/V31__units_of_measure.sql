-- FP1 W2a / V31: units of measure and catalog columns on inventory_items. Additive; no rows seeded.
CREATE TABLE IF NOT EXISTS units_of_measure (
  id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id  UUID NOT NULL REFERENCES tenants(id),
  code       VARCHAR(20)  NOT NULL,
  name       VARCHAR(100) NOT NULL,
  name_ar    VARCHAR(100) NOT NULL,
  decimals   SMALLINT NOT NULL DEFAULT 0 CHECK (decimals BETWEEN 0 AND 3),
  is_active  BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  deleted_at TIMESTAMPTZ,
  CONSTRAINT uq_uom_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_uom_id_tenant UNIQUE (id, tenant_id)
);
DROP TRIGGER IF EXISTS trg_uom_updated ON units_of_measure;
CREATE TRIGGER trg_uom_updated BEFORE UPDATE ON units_of_measure
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE inventory_items
  ADD COLUMN IF NOT EXISTS uom_id UUID REFERENCES units_of_measure(id),
  ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN IF NOT EXISTS low_stock_notified_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS category VARCHAR(80);

COMMENT ON COLUMN inventory_items.quantity IS
  'LEGACY, not maintained since V32: stock lives in stock_balances (sum of on_hand per item).';

CREATE INDEX IF NOT EXISTS idx_inventory_items_tenant_center ON inventory_items(tenant_id, center_id);

-- tenant required: only when no legacy row lacks one (never guess a tenant)
DO $$
DECLARE n INT;
BEGIN
  SELECT count(*) INTO n FROM inventory_items WHERE tenant_id IS NULL;
  IF n = 0 THEN
    ALTER TABLE inventory_items ALTER COLUMN tenant_id SET NOT NULL;
  ELSE
    RAISE NOTICE 'V31: % inventory_items without tenant, tenant_id stays nullable', n;
  END IF;
END $$;
