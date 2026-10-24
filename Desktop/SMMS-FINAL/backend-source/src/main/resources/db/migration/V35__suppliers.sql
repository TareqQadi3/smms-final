-- V35: suppliers (tenant-level master data for procurement). Additive only.
CREATE TABLE IF NOT EXISTS suppliers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL REFERENCES tenants(id),
    code        VARCHAR(40)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    name_ar     VARCHAR(200),
    phone       VARCHAR(40),
    email       VARCHAR(255),
    tax_number  VARCHAR(40),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT uq_suppliers_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_suppliers_id_tenant UNIQUE (id, tenant_id)
);
CREATE INDEX IF NOT EXISTS idx_suppliers_tenant_name ON suppliers(tenant_id, name);

DROP TRIGGER IF EXISTS trg_suppliers_updated ON suppliers;
CREATE TRIGGER trg_suppliers_updated BEFORE UPDATE ON suppliers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
