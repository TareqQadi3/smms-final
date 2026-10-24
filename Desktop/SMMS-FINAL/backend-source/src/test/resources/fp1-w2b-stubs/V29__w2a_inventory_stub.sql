-- TEST FIXTURE ONLY (W2b). Minimal compatible stand-in for the inventory block (V30-V34, agent W2a)
-- so that V35-V39 can be migrated and tested before W2a is merged. Active only when no real
-- db/migration/V30__*.sql is on the classpath (see W2bTestSupportConfig). Shapes follow the plan 2.5.
CREATE TABLE IF NOT EXISTS warehouses (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES tenants(id),
    center_id  UUID NOT NULL REFERENCES centers(id),
    code       VARCHAR(40) NOT NULL,
    name       VARCHAR(200) NOT NULL,
    name_ar    VARCHAR(200),
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at TIMESTAMPTZ,
    UNIQUE (center_id, code),
    UNIQUE (id, tenant_id)
);
CREATE TABLE IF NOT EXISTS units_of_measure (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    code      VARCHAR(20) NOT NULL,
    name      VARCHAR(100) NOT NULL,
    decimals  SMALLINT NOT NULL DEFAULT 0 CHECK (decimals BETWEEN 0 AND 3),
    UNIQUE (tenant_id, code)
);
ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS uom_id UUID REFERENCES units_of_measure(id);
CREATE TABLE IF NOT EXISTS stock_balances (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES tenants(id),
    item_id      UUID NOT NULL REFERENCES inventory_items(id),
    warehouse_id UUID NOT NULL,
    on_hand      NUMERIC(14,3) NOT NULL DEFAULT 0,
    reserved     NUMERIC(14,3) NOT NULL DEFAULT 0,
    min_qty      NUMERIC(14,3),
    version      BIGINT NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (item_id, warehouse_id),
    FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses(id, tenant_id),
    CHECK (on_hand >= 0),
    CHECK (reserved >= 0 AND reserved <= on_hand)
);
ALTER TABLE inventory_movements
    ADD COLUMN IF NOT EXISTS warehouse_id UUID REFERENCES warehouses(id),
    ADD COLUMN IF NOT EXISTS movement_kind VARCHAR(20),
    ADD COLUMN IF NOT EXISTS reason_code VARCHAR(30),
    ADD COLUMN IF NOT EXISTS reversal_of_id UUID REFERENCES inventory_movements(id),
    ADD COLUMN IF NOT EXISTS material_request_line_id UUID,
    ADD COLUMN IF NOT EXISTS po_receipt_line_id UUID,
    ADD COLUMN IF NOT EXISTS balance_after NUMERIC(14,3),
    ADD COLUMN IF NOT EXISTS reference VARCHAR(120),
    ADD COLUMN IF NOT EXISTS idempotency_record_id UUID;
