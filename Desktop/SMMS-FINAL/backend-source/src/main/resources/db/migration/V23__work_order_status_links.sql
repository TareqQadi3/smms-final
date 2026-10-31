-- V23: public request-status links (workflow program, WS1). Only the SHA-256 (hex) of the random
-- 256-bit token is stored; the token itself is shown once to the requester. At most one active
-- (not revoked) link per work order: creating a new one revokes the previous one.

CREATE TABLE IF NOT EXISTS work_order_status_links (
  id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id     UUID NOT NULL REFERENCES tenants(id),
  work_order_id UUID NOT NULL REFERENCES work_orders(id),
  token_hash    VARCHAR(64) NOT NULL UNIQUE,
  created_by_id UUID NOT NULL REFERENCES users(id),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at    TIMESTAMPTZ NOT NULL,
  revoked_at    TIMESTAMPTZ,
  CONSTRAINT chk_status_link_expiry CHECK (expires_at > created_at)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_status_link_active
  ON work_order_status_links(work_order_id) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_status_links_tenant ON work_order_status_links(tenant_id);
