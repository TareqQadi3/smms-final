-- V11: server-side login sessions (refresh-token rotation with reuse detection, logout revocation)
-- and the per-user columns that let an admin invalidate every token of an account.
-- Additive only: no existing row is rewritten. Hashes are VARCHAR(64) (hex sha-256) so that
-- hibernate ddl-auto=validate sees a plain varchar.

CREATE TABLE IF NOT EXISTS auth_sessions (
    id                UUID PRIMARY KEY,
    user_id           UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id         UUID,
    current_jti_hash  VARCHAR(64) NOT NULL,
    previous_jti_hash VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_refreshed_at TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ NOT NULL,
    revoked_at        TIMESTAMPTZ,
    revoke_reason     VARCHAR(40)
);

CREATE INDEX IF NOT EXISTS idx_auth_sessions_user_active
    ON auth_sessions (user_id) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_auth_sessions_expires
    ON auth_sessions (expires_at);

-- metadata-only on PostgreSQL 11+ (constant default)
ALTER TABLE users ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
-- tokens issued before this instant are refused (set on deactivation and admin password reset)
ALTER TABLE users ADD COLUMN IF NOT EXISTS tokens_valid_after TIMESTAMPTZ;
