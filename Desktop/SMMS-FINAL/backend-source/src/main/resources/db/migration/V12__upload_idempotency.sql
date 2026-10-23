-- V12: idempotent attachment uploads. A client may send an Idempotency-Key header; the first
-- request with a (user, key) pair owns the row, a retry with the same files replays the result.
-- Additive only.

CREATE TABLE IF NOT EXISTS upload_requests (
    id            UUID PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    work_order_id UUID        NOT NULL REFERENCES work_orders(id) ON DELETE CASCADE,
    idem_key      VARCHAR(64) NOT NULL,
    fingerprint   VARCHAR(64) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_upload_requests_user_key UNIQUE (user_id, idem_key)
);

CREATE INDEX IF NOT EXISTS idx_upload_requests_created ON upload_requests (created_at);

ALTER TABLE attachments
    ADD COLUMN IF NOT EXISTS upload_request_id UUID REFERENCES upload_requests(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_attachments_upload_request
    ON attachments (upload_request_id) WHERE upload_request_id IS NOT NULL;
