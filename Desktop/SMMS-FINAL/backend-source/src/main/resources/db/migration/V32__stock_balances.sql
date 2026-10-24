-- FP1 W2a / V32: stock balances per (item, warehouse). CHECKs are the no-negative-stock backstop.
CREATE TABLE IF NOT EXISTS stock_balances (
  id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id    UUID NOT NULL REFERENCES tenants(id),
  item_id      UUID NOT NULL REFERENCES inventory_items(id),
  warehouse_id UUID NOT NULL,
  on_hand      NUMERIC(14,3) NOT NULL DEFAULT 0,
  reserved     NUMERIC(14,3) NOT NULL DEFAULT 0,
  min_qty      NUMERIC(14,3),
  version      BIGINT NOT NULL DEFAULT 0,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_stock_balances_item_wh UNIQUE (item_id, warehouse_id),
  CONSTRAINT fk_stock_balances_wh FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses(id, tenant_id),
  CONSTRAINT fk_stock_balances_item FOREIGN KEY (item_id, tenant_id) REFERENCES inventory_items(id, tenant_id),
  CONSTRAINT ck_stock_balances_on_hand CHECK (on_hand >= 0),
  CONSTRAINT ck_stock_balances_reserved CHECK (reserved >= 0 AND reserved <= on_hand),
  CONSTRAINT ck_stock_balances_min CHECK (min_qty IS NULL OR min_qty >= 0)
);
CREATE INDEX IF NOT EXISTS idx_stock_balances_wh ON stock_balances(warehouse_id, item_id);
CREATE INDEX IF NOT EXISTS idx_stock_balances_tenant ON stock_balances(tenant_id);
DROP TRIGGER IF EXISTS trg_stock_balances_updated ON stock_balances;
CREATE TRIGGER trg_stock_balances_updated BEFORE UPDATE ON stock_balances
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Opening balances from the legacy quantity, only where V30 created the MAIN warehouse of the
-- item's own center and tenant. The matching 'opening' movements are written in V33 (they need its columns).
DO $$
DECLARE n INT;
BEGIN
  INSERT INTO stock_balances (tenant_id, item_id, warehouse_id, on_hand)
  SELECT i.tenant_id, i.id, w.id, i.quantity
    FROM inventory_items i
    JOIN warehouses w ON w.center_id = i.center_id AND w.code = 'MAIN' AND w.tenant_id = i.tenant_id
   WHERE i.quantity > 0 AND i.deleted_at IS NULL
  ON CONFLICT (item_id, warehouse_id) DO NOTHING;
  GET DIAGNOSTICS n = ROW_COUNT;
  RAISE NOTICE 'V32: % opening balance(s) created', n;
END $$;
