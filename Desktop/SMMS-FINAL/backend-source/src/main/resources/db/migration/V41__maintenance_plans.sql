-- V41: preventive maintenance plans. A plan generates a normal work order every
-- frequency_interval x frequency_unit starting at start_date; next_due_date is advanced by the
-- generation job in the same transaction as the work order. start_date is the anchor of the date
-- series (so Jan 31 + 1 month = Feb 28, then Mar 31). Additive only.

CREATE TABLE IF NOT EXISTS maintenance_plans (
    id                 UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id          UUID         NOT NULL REFERENCES tenants(id),
    center_id          UUID         NOT NULL REFERENCES centers(id),
    asset_id           UUID         NOT NULL,
    control_room_id    UUID         NOT NULL REFERENCES control_rooms(id),
    sub_type           sub_type     NOT NULL,
    priority           ticket_priority NOT NULL DEFAULT 'medium',
    title              VARCHAR(300) NOT NULL,
    instructions       TEXT,
    frequency_unit     VARCHAR(10)  NOT NULL,
    frequency_interval INT          NOT NULL,
    lead_days          INT          NOT NULL DEFAULT 0,
    start_date         DATE         NOT NULL,
    next_due_date      DATE         NOT NULL,
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    owner_id           UUID         NOT NULL REFERENCES users(id),
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT chk_plans_frequency_unit CHECK (frequency_unit IN ('day','week','month','year')),
    CONSTRAINT chk_plans_frequency_interval CHECK (frequency_interval BETWEEN 1 AND 366),
    CONSTRAINT chk_plans_lead_days CHECK (lead_days BETWEEN 0 AND 60),
    CONSTRAINT chk_plans_due_after_start CHECK (next_due_date >= start_date),
    CONSTRAINT uq_plans_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT fk_plans_asset_tenant FOREIGN KEY (asset_id, tenant_id) REFERENCES assets (id, tenant_id)
);

CREATE INDEX IF NOT EXISTS idx_plans_due ON maintenance_plans (is_active, next_due_date) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_plans_tenant_center ON maintenance_plans (tenant_id, center_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_plans_asset ON maintenance_plans (asset_id) WHERE deleted_at IS NULL;

DROP TRIGGER IF EXISTS trg_maintenance_plans_updated ON maintenance_plans;
CREATE TRIGGER trg_maintenance_plans_updated
    BEFORE UPDATE ON maintenance_plans
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
