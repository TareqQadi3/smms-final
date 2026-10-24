-- V51: delivery log of external (email / sms) notification channels (FP1 W4).
-- One row per (notification, channel). The in-app notification row is the source of truth and is
-- never modified by delivery. Status meaning:
--   pending              queued, not tried yet
--   failed               last attempt failed, will be retried at next_attempt_at
--   sent                 the provider ACCEPTED the message (not a read/delivery receipt)
--   dead                 gave up after the maximum number of attempts or a permanent error
--   not_configured       no usable provider for the channel: nothing was sent (terminal)
--   skipped_no_address   the user has no usable address for the channel (terminal)

CREATE TABLE IF NOT EXISTS notification_deliveries (
  id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id           UUID NOT NULL REFERENCES tenants(id),
  notification_id     UUID NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
  user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  channel             VARCHAR(10) NOT NULL CHECK (channel IN ('email', 'sms')),
  destination_masked  VARCHAR(80),
  status              VARCHAR(25) NOT NULL DEFAULT 'pending'
      CHECK (status IN ('pending', 'sent', 'failed', 'dead', 'not_configured', 'skipped_no_address')),
  attempts            INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error          VARCHAR(500),
  provider            VARCHAR(30),
  provider_message_id VARCHAR(120),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  sent_at             TIMESTAMPTZ,
  CONSTRAINT uq_notification_delivery UNIQUE (notification_id, channel),
  CONSTRAINT chk_delivery_sent_at CHECK (status <> 'sent' OR sent_at IS NOT NULL)
);

-- dispatcher: due rows only
CREATE INDEX IF NOT EXISTS idx_notif_delivery_due ON notification_deliveries(next_attempt_at)
  WHERE status IN ('pending', 'failed');
-- admin listing
CREATE INDEX IF NOT EXISTS idx_notif_delivery_tenant ON notification_deliveries(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notif_delivery_user ON notification_deliveries(user_id);
