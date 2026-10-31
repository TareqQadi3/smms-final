-- V14: generic idempotency records (stock operations, work-order actions). The first request with a
-- (user, scope, key) triple owns the row; a retry with the same fingerprint replays result_ref, a
-- retry with a different fingerprint is refused. Attachment uploads keep their own V12 table.
-- Additive only.

CREATE TABLE IF NOT EXISTS idempotency_records (
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NULL REFERENCES tenants(id),   -- NULL = platform admin
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope       VARCHAR(60) NOT NULL,
    idem_key    VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    result_ref  UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency_user_scope_key UNIQUE (user_id, scope, idem_key)
);

-- 7-day cleanup scans by age
CREATE INDEX IF NOT EXISTS idx_idempotency_created ON idempotency_records (created_at);
