-- V40: asset register columns (identifiers, classification, warranty, criticality, decommissioning,
-- optimistic lock). Additive only; existing rows keep NULL / neutral defaults.

ALTER TABLE assets
    ADD COLUMN IF NOT EXISTS asset_code         VARCHAR(60),
    ADD COLUMN IF NOT EXISTS category           VARCHAR(80),
    ADD COLUMN IF NOT EXISTS manufacturer       VARCHAR(120),
    ADD COLUMN IF NOT EXISTS model              VARCHAR(120),
    ADD COLUMN IF NOT EXISTS install_date       DATE,
    ADD COLUMN IF NOT EXISTS warranty_expiry    DATE,
    ADD COLUMN IF NOT EXISTS criticality        VARCHAR(10),
    ADD COLUMN IF NOT EXISTS decommissioned_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS decommission_reason TEXT,
    ADD COLUMN IF NOT EXISTS version            BIGINT NOT NULL DEFAULT 0;

ALTER TABLE assets DROP CONSTRAINT IF EXISTS chk_assets_criticality;
ALTER TABLE assets ADD CONSTRAINT chk_assets_criticality
    CHECK (criticality IS NULL OR criticality IN ('low','medium','high','critical'));

ALTER TABLE assets DROP CONSTRAINT IF EXISTS chk_assets_warranty_after_install;
ALTER TABLE assets ADD CONSTRAINT chk_assets_warranty_after_install
    CHECK (install_date IS NULL OR warranty_expiry IS NULL OR warranty_expiry >= install_date);

-- a decommissioned asset always carries a reason
ALTER TABLE assets DROP CONSTRAINT IF EXISTS chk_assets_decommission_reason;
ALTER TABLE assets ADD CONSTRAINT chk_assets_decommission_reason
    CHECK (decommissioned_at IS NULL OR (decommission_reason IS NOT NULL AND length(btrim(decommission_reason)) > 0));

-- one asset code per center among live rows (codes are optional)
CREATE UNIQUE INDEX IF NOT EXISTS uq_assets_center_code
    ON assets (center_id, asset_code)
    WHERE deleted_at IS NULL AND asset_code IS NOT NULL;

-- target of composite (asset_id, tenant_id) foreign keys of the PM and downtime tables
CREATE UNIQUE INDEX IF NOT EXISTS uq_assets_id_tenant ON assets (id, tenant_id);

CREATE INDEX IF NOT EXISTS idx_assets_tenant_status ON assets (tenant_id, status);
