-- V50: per-user opt-in for external notification channels (FP1 W4).
-- In-app notifications are unaffected: no row here means "in-app only" (both channels off).
-- Additive only. The *_enabled_at columns record when a channel was switched on, so the
-- delivery enqueuer never back-fills notifications created before the user opted in.

CREATE TABLE IF NOT EXISTS notification_preferences (
  user_id          UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  tenant_id        UUID NOT NULL REFERENCES tenants(id),
  email_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
  sms_enabled      BOOLEAN NOT NULL DEFAULT FALSE,
  email_enabled_at TIMESTAMPTZ,
  sms_enabled_at   TIMESTAMPTZ,
  -- NULL = every notification type; otherwise only these notifications.type values
  types            TEXT[],
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_notif_pref_types_size CHECK (types IS NULL OR cardinality(types) BETWEEN 1 AND 50)
);

CREATE INDEX IF NOT EXISTS idx_notif_pref_tenant ON notification_preferences(tenant_id);

DROP TRIGGER IF EXISTS trg_notification_preferences_updated ON notification_preferences;
CREATE TRIGGER trg_notification_preferences_updated
  BEFORE UPDATE ON notification_preferences
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
