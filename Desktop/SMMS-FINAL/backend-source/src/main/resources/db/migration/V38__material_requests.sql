-- V38: work-order material requests and lines (quantity arithmetic enforced by CHECKs).
CREATE SEQUENCE IF NOT EXISTS mr_seq START 1;

CREATE TABLE IF NOT EXISTS material_requests (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    request_number  VARCHAR(30) NOT NULL UNIQUE,
    center_id       UUID        NOT NULL REFERENCES centers(id),
    work_order_id   UUID        NOT NULL REFERENCES work_orders(id),
    warehouse_id    UUID        NOT NULL,
    requested_by_id UUID        NOT NULL REFERENCES users(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'submitted'
        CHECK (status IN ('submitted','approved','rejected','reserved','issued','closed','cancelled')),
    note            TEXT,
    decided_by_id   UUID        REFERENCES users(id),
    decided_at      TIMESTAMPTZ,
    decision_note   TEXT,
    cancel_reason   TEXT,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_mr_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT fk_mr_warehouse FOREIGN KEY (warehouse_id, tenant_id) REFERENCES warehouses(id, tenant_id)
);
CREATE INDEX IF NOT EXISTS idx_mr_tenant_status ON material_requests(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_mr_work_order ON material_requests(work_order_id);
CREATE INDEX IF NOT EXISTS idx_mr_center ON material_requests(center_id, created_at);

CREATE OR REPLACE FUNCTION generate_mr_number() RETURNS TRIGGER AS $$
BEGIN
  NEW.request_number := 'MR-' || TO_CHAR(NOW(), 'YYYY') || '-' || LPAD(NEXTVAL('mr_seq')::TEXT, 5, '0');
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_mr_number ON material_requests;
CREATE TRIGGER trg_mr_number BEFORE INSERT ON material_requests
    FOR EACH ROW EXECUTE FUNCTION generate_mr_number();
DROP TRIGGER IF EXISTS trg_mr_updated ON material_requests;
CREATE TRIGGER trg_mr_updated BEFORE UPDATE ON material_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE IF NOT EXISTS material_request_lines (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID          NOT NULL REFERENCES tenants(id),
    mr_id         UUID          NOT NULL,
    item_id       UUID          NOT NULL REFERENCES inventory_items(id),
    qty_requested NUMERIC(14,3) NOT NULL CHECK (qty_requested > 0),
    qty_approved  NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (qty_approved >= 0),
    qty_reserved  NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (qty_reserved >= 0),
    qty_issued    NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (qty_issued >= 0),
    qty_consumed  NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (qty_consumed >= 0),
    qty_returned  NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (qty_returned >= 0),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_mrl_mr FOREIGN KEY (mr_id, tenant_id) REFERENCES material_requests(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT uq_mrl_mr_item UNIQUE (mr_id, item_id),
    CONSTRAINT uq_mrl_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT chk_mrl_approved_le_requested CHECK (qty_approved <= qty_requested),
    CONSTRAINT chk_mrl_issued_le_approved CHECK (qty_issued <= qty_approved),
    CONSTRAINT chk_mrl_used_le_issued CHECK (qty_consumed + qty_returned <= qty_issued),
    -- stricter than the plan: a held reservation never exceeds what is still to be issued
    CONSTRAINT chk_mrl_reserved_le_open CHECK (qty_reserved + qty_issued <= qty_approved)
);
CREATE INDEX IF NOT EXISTS idx_mrl_item ON material_request_lines(item_id);

DROP TRIGGER IF EXISTS trg_mrl_updated ON material_request_lines;
CREATE TRIGGER trg_mrl_updated BEFORE UPDATE ON material_request_lines
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
