-- V43: asset downtime periods. At most one open period per asset (partial unique index); closed
-- periods are checked for overlap by the service. Additive only.

CREATE TABLE IF NOT EXISTS asset_downtime (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id      UUID         NOT NULL REFERENCES tenants(id),
    asset_id       UUID         NOT NULL,
    work_order_id  UUID         REFERENCES work_orders(id),
    started_at     TIMESTAMPTZ  NOT NULL,
    ended_at       TIMESTAMPTZ,
    reason         VARCHAR(500) NOT NULL,
    recorded_by_id UUID         NOT NULL REFERENCES users(id),
    ended_by_id    UUID         REFERENCES users(id),
    -- true when starting this period switched the asset from active to under_maintenance; ending it
    -- restores active only then, and only if the status was not changed by hand in between
    marked_asset   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_downtime_order CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT fk_downtime_asset_tenant FOREIGN KEY (asset_id, tenant_id) REFERENCES assets (id, tenant_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_downtime_one_open ON asset_downtime (asset_id) WHERE ended_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_downtime_asset_started ON asset_downtime (asset_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_downtime_work_order ON asset_downtime (work_order_id) WHERE work_order_id IS NOT NULL;

DROP TRIGGER IF EXISTS trg_asset_downtime_updated ON asset_downtime;
CREATE TRIGGER trg_asset_downtime_updated
    BEFORE UPDATE ON asset_downtime
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
