-- FP1 W2a / V33: extend inventory_movements into the stock ledger. Additive; legacy rows keep
-- movement_kind NULL (no warehouse, no balance_after).
ALTER TABLE inventory_movements
  ADD COLUMN IF NOT EXISTS warehouse_id UUID REFERENCES warehouses(id),
  ADD COLUMN IF NOT EXISTS movement_kind VARCHAR(20),
  ADD COLUMN IF NOT EXISTS reason_code VARCHAR(30),
  ADD COLUMN IF NOT EXISTS reversal_of_id UUID REFERENCES inventory_movements(id),
  ADD COLUMN IF NOT EXISTS material_request_line_id UUID,
  ADD COLUMN IF NOT EXISTS po_receipt_line_id UUID,
  ADD COLUMN IF NOT EXISTS balance_after NUMERIC(14,3),
  ADD COLUMN IF NOT EXISTS reference VARCHAR(120),
  ADD COLUMN IF NOT EXISTS idempotency_record_id UUID,
  ADD COLUMN IF NOT EXISTS seq BIGINT GENERATED ALWAYS AS IDENTITY;

-- widening only (12,2 -> 14,3): lossless for existing rows
ALTER TABLE inventory_movements ALTER COLUMN quantity TYPE NUMERIC(14,3);

ALTER TABLE inventory_movements DROP CONSTRAINT IF EXISTS ck_inv_mov_kind;
ALTER TABLE inventory_movements ADD CONSTRAINT ck_inv_mov_kind CHECK (movement_kind IS NULL OR movement_kind IN
  ('opening','receipt','issue','return_in','adjustment_in','adjustment_out','reversal_in','reversal_out'));
-- a ledger row (kind set) always has warehouse and balance
ALTER TABLE inventory_movements DROP CONSTRAINT IF EXISTS ck_inv_mov_ledger;
ALTER TABLE inventory_movements ADD CONSTRAINT ck_inv_mov_ledger CHECK
  (movement_kind IS NULL OR (warehouse_id IS NOT NULL AND balance_after IS NOT NULL AND balance_after >= 0 AND tenant_id IS NOT NULL));
-- a reversal names what it reverses; nothing else does
ALTER TABLE inventory_movements DROP CONSTRAINT IF EXISTS ck_inv_mov_reversal;
ALTER TABLE inventory_movements ADD CONSTRAINT ck_inv_mov_reversal CHECK
  (movement_kind IS NULL OR ((movement_kind IN ('reversal_in','reversal_out')) = (reversal_of_id IS NOT NULL)));
-- corrections and reversals always carry a reason code and a real note
ALTER TABLE inventory_movements DROP CONSTRAINT IF EXISTS ck_inv_mov_reason;
ALTER TABLE inventory_movements ADD CONSTRAINT ck_inv_mov_reason CHECK
  (movement_kind IS NULL OR movement_kind NOT IN ('adjustment_in','adjustment_out','reversal_in','reversal_out')
   OR (reason_code IS NOT NULL AND note IS NOT NULL AND length(btrim(note)) >= 5));

DO $$
DECLARE bad INT;
BEGIN
  SELECT count(*) INTO bad FROM inventory_movements WHERE NOT (quantity > 0);
  ALTER TABLE inventory_movements DROP CONSTRAINT IF EXISTS ck_inv_mov_qty_pos;
  ALTER TABLE inventory_movements ADD CONSTRAINT ck_inv_mov_qty_pos CHECK (quantity > 0) NOT VALID;
  IF bad = 0 THEN
    ALTER TABLE inventory_movements VALIDATE CONSTRAINT ck_inv_mov_qty_pos;
  ELSE
    RAISE NOTICE 'V33: % legacy movement(s) with quantity <= 0, quantity check left NOT VALID', bad;
  END IF;

  -- tenant pairing for new rows; validated only when the legacy rows already agree
  ALTER TABLE inventory_movements DROP CONSTRAINT IF EXISTS fk_inv_mov_item_tenant;
  ALTER TABLE inventory_movements ADD CONSTRAINT fk_inv_mov_item_tenant
    FOREIGN KEY (item_id, tenant_id) REFERENCES inventory_items(id, tenant_id) NOT VALID;
  SELECT count(*) INTO bad FROM inventory_movements m LEFT JOIN inventory_items i ON i.id = m.item_id AND i.tenant_id = m.tenant_id
   WHERE m.tenant_id IS NOT NULL AND i.id IS NULL;
  IF bad = 0 THEN ALTER TABLE inventory_movements VALIDATE CONSTRAINT fk_inv_mov_item_tenant;
  ELSE RAISE NOTICE 'V33: % legacy movement(s) with tenant mismatch, item/tenant FK left NOT VALID', bad; END IF;

  ALTER TABLE inventory_movements DROP CONSTRAINT IF EXISTS fk_inv_mov_wh_tenant;
  ALTER TABLE inventory_movements ADD CONSTRAINT fk_inv_mov_wh_tenant
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses(id, tenant_id);

  SELECT count(*) INTO bad FROM inventory_movements WHERE tenant_id IS NULL;
  IF bad = 0 THEN ALTER TABLE inventory_movements ALTER COLUMN tenant_id SET NOT NULL;
  ELSE RAISE NOTICE 'V33: % inventory_movements without tenant, tenant_id stays nullable', bad; END IF;
END $$;

-- one reversal per movement
CREATE UNIQUE INDEX IF NOT EXISTS uq_inv_mov_reversal_of ON inventory_movements(reversal_of_id) WHERE reversal_of_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_inv_mov_tenant_created ON inventory_movements(tenant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_inv_mov_work_order ON inventory_movements(work_order_id) WHERE work_order_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_inv_mov_wh_item_seq ON inventory_movements(warehouse_id, item_id, seq);
CREATE INDEX IF NOT EXISTS idx_inv_mov_idem ON inventory_movements(idempotency_record_id) WHERE idempotency_record_id IS NOT NULL;

-- opening movements matching the V32 opening balances (balance_after = quantity)
DO $$
DECLARE n INT;
BEGIN
  INSERT INTO inventory_movements (tenant_id, item_id, warehouse_id, type, movement_kind, quantity, balance_after, reason_code, note)
  SELECT b.tenant_id, b.item_id, b.warehouse_id, 'stock_in', 'opening', b.on_hand, b.on_hand, NULL, 'Opening balance from legacy quantity (V33)'
    FROM stock_balances b
   WHERE b.on_hand > 0
     AND NOT EXISTS (SELECT 1 FROM inventory_movements m WHERE m.item_id = b.item_id
                      AND m.warehouse_id = b.warehouse_id AND m.movement_kind = 'opening');
  GET DIAGNOSTICS n = ROW_COUNT;
  RAISE NOTICE 'V33: % opening movement(s) created', n;
END $$;
