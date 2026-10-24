-- V36: purchase orders and lines. Depends on V30 (warehouses, UNIQUE (id, tenant_id)) from the
-- inventory block. Separation of duties is enforced by a CHECK (approver <> creator).
CREATE SEQUENCE IF NOT EXISTS po_seq START 1;

CREATE TABLE IF NOT EXISTS purchase_orders (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants(id),
    po_number           VARCHAR(30) NOT NULL UNIQUE,
    center_id           UUID        NOT NULL REFERENCES centers(id),
    supplier_id         UUID        NOT NULL,
    warehouse_id        UUID        NOT NULL,
    work_order_id       UUID        REFERENCES work_orders(id),
    material_request_id UUID,
    status              VARCHAR(20) NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft','submitted','approved','rejected','partially_received','received','cancelled','closed')),
    created_by_id       UUID        NOT NULL REFERENCES users(id),
    submitted_at        TIMESTAMPTZ,
    approved_by_id      UUID        REFERENCES users(id),
    approved_at         TIMESTAMPTZ,
    decision_reason     TEXT,
    expected_date       DATE,
    notes               TEXT,
    version             BIGINT      NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_po_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT fk_po_supplier  FOREIGN KEY (supplier_id, tenant_id)  REFERENCES suppliers(id, tenant_id),
    CONSTRAINT fk_po_warehouse FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses(id, tenant_id),
    CONSTRAINT chk_po_sod CHECK (approved_by_id IS NULL OR approved_by_id <> created_by_id)
);
CREATE INDEX IF NOT EXISTS idx_po_tenant_status ON purchase_orders(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_po_center ON purchase_orders(center_id, created_at);
CREATE INDEX IF NOT EXISTS idx_po_supplier ON purchase_orders(supplier_id);

CREATE OR REPLACE FUNCTION generate_po_number() RETURNS TRIGGER AS $$
BEGIN
  NEW.po_number := 'PO-' || TO_CHAR(NOW(), 'YYYY') || '-' || LPAD(NEXTVAL('po_seq')::TEXT, 5, '0');
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_po_number ON purchase_orders;
CREATE TRIGGER trg_po_number BEFORE INSERT ON purchase_orders
    FOR EACH ROW EXECUTE FUNCTION generate_po_number();
DROP TRIGGER IF EXISTS trg_po_updated ON purchase_orders;
CREATE TRIGGER trg_po_updated BEFORE UPDATE ON purchase_orders
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE IF NOT EXISTS purchase_order_lines (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID          NOT NULL REFERENCES tenants(id),
    po_id        UUID          NOT NULL,
    line_no      INT           NOT NULL CHECK (line_no > 0),
    item_id      UUID          NOT NULL REFERENCES inventory_items(id),
    qty_ordered  NUMERIC(14,3) NOT NULL CHECK (qty_ordered > 0),
    qty_received NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (qty_received >= 0),
    unit_price   NUMERIC(14,2) CHECK (unit_price IS NULL OR unit_price >= 0),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_pol_po FOREIGN KEY (po_id, tenant_id) REFERENCES purchase_orders(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT uq_pol_po_line UNIQUE (po_id, line_no),
    CONSTRAINT uq_pol_po_item UNIQUE (po_id, item_id),
    CONSTRAINT uq_pol_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT chk_pol_received_le_ordered CHECK (qty_received <= qty_ordered)
);
CREATE INDEX IF NOT EXISTS idx_pol_item ON purchase_order_lines(item_id);

DROP TRIGGER IF EXISTS trg_pol_updated ON purchase_order_lines;
CREATE TRIGGER trg_pol_updated BEFORE UPDATE ON purchase_order_lines
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
