-- V44: indexes for the asset / PM reports and the per-asset history lists. Additive only.

CREATE INDEX IF NOT EXISTS idx_plan_runs_tenant_due ON maintenance_plan_runs (tenant_id, due_date);
CREATE INDEX IF NOT EXISTS idx_downtime_tenant_started ON asset_downtime (tenant_id, started_at);
CREATE INDEX IF NOT EXISTS idx_assets_tenant_center ON assets (tenant_id, center_id) WHERE deleted_at IS NULL;
