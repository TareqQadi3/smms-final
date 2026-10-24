-- V37: goods receipts against purchase orders. movement_id is set after the ledger row exists.
CREATE TABLE IF NOT EXISTS po_receipts (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID        NOT NULL REFERENCES tenants(id),
    po_id                 UUID        NOT NULL,
    warehouse_id          UUID        NOT NULL,
    received_by_id        UUID        NOT NULL REFERENCES users(id),
    received_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    note                  TEXT,
    idempotency_record_id UUID,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_receipt_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT fk_receipt_po FOREIGN KEY (po_id, tenant_id) REFERENCES purchase_orders(id, tenant_id),
    CONSTRAINT fk_receipt_warehouse FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses(id, tenant_id)
);
CREATE INDEX IF NOT EXISTS idx_receipt_po ON po_receipts(po_id);

CREATE TABLE IF NOT EXISTS po_receipt_lines (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID          NOT NULL REFERENCES tenants(id),
    receipt_id  UUID          NOT NULL,
    po_line_id  UUID          NOT NULL,
    quantity    NUMERIC(14,3) NOT NULL CHECK (quantity > 0),
    movement_id UUID          UNIQUE REFERENCES inventory_movements(id),
    CONSTRAINT fk_rl_receipt FOREIGN KEY (receipt_id, tenant_id) REFERENCES po_receipts(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_rl_poline  FOREIGN KEY (po_line_id, tenant_id) REFERENCES purchase_order_lines(id, tenant_id),
    CONSTRAINT uq_rl_receipt_line UNIQUE (receipt_id, po_line_id)
);
CREATE INDEX IF NOT EXISTS idx_rl_poline ON po_receipt_lines(po_line_id);
