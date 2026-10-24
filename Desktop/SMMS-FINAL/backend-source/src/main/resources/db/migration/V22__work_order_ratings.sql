-- V22: requester rating of a finished work order (workflow program, WS1). One rating per work order.

CREATE TABLE IF NOT EXISTS work_order_ratings (
  id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id     UUID NOT NULL REFERENCES tenants(id),
  work_order_id UUID NOT NULL UNIQUE REFERENCES work_orders(id),
  rated_by_id   UUID NOT NULL REFERENCES users(id),
  score         SMALLINT NOT NULL CHECK (score BETWEEN 1 AND 5),
  comment       TEXT CHECK (comment IS NULL OR length(comment) <= 1000),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ratings_tenant_created ON work_order_ratings(tenant_id, created_at);
