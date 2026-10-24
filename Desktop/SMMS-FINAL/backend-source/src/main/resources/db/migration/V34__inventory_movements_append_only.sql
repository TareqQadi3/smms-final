-- FP1 W2a / V34: the ledger is append-only. UPDATE and DELETE are rejected for every client
-- (TRUNCATE is not covered on purpose: restore rehearsals still work).
DROP TRIGGER IF EXISTS trg_inventory_movements_updated ON inventory_movements;

CREATE OR REPLACE FUNCTION inventory_movements_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'inventory_movements is append-only' USING ERRCODE = '23000';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_inventory_movements_append_only ON inventory_movements;
CREATE TRIGGER trg_inventory_movements_append_only
  BEFORE UPDATE OR DELETE ON inventory_movements
  FOR EACH ROW EXECUTE FUNCTION inventory_movements_append_only();
