-- V20: structured "request for information" records (workflow program, WS1).
-- Until now a request/answer only existed as free text in ticket_history. Each row is one question
-- from staff and (once answered or cancelled) its closure. At most one request per work order is
-- open at a time (partial unique index). SLA pause accounting uses work_orders.sla_paused_* (V13).

CREATE TABLE IF NOT EXISTS work_order_info_requests (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id       UUID NOT NULL REFERENCES tenants(id),
  work_order_id   UUID NOT NULL REFERENCES work_orders(id),
  requested_by_id UUID NOT NULL REFERENCES users(id),
  question        TEXT NOT NULL,
  requested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  answered_by_id  UUID REFERENCES users(id),
  answer          TEXT,
  answered_at     TIMESTAMPTZ,
  cancelled_at    TIMESTAMPTZ,
  -- seconds the SLA clock was paused for this request (set when it is answered or cancelled)
  paused_seconds  BIGINT CHECK (paused_seconds IS NULL OR paused_seconds >= 0),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_info_req_answered_after CHECK (answered_at IS NULL OR answered_at >= requested_at),
  CONSTRAINT chk_info_req_not_both CHECK (answered_at IS NULL OR cancelled_at IS NULL)
);

-- one open request per work order: the duplicate-request guard that holds under concurrency
CREATE UNIQUE INDEX IF NOT EXISTS uq_info_req_open
  ON work_order_info_requests(work_order_id) WHERE answered_at IS NULL AND cancelled_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_info_req_wo ON work_order_info_requests(work_order_id, requested_at);
CREATE INDEX IF NOT EXISTS idx_info_req_tenant ON work_order_info_requests(tenant_id, created_at);

DROP TRIGGER IF EXISTS trg_info_requests_updated ON work_order_info_requests;
CREATE TRIGGER trg_info_requests_updated
  BEFORE UPDATE ON work_order_info_requests
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Backfill: tickets that are waiting for information right now get one open row, built only from
-- the verified history row that opened the request (actor and text). Tickets without such a row
-- are skipped (never guessed) and counted. The SLA is NOT paused retroactively (that would extend
-- deadlines silently): sla_paused_at stays NULL for these legacy requests.
DO $$
DECLARE
  inserted INT;
  skipped  INT;
BEGIN
  WITH legacy AS (
    SELECT w.id AS wo_id, w.tenant_id,
           h.actor_id, h.created_at AS req_at,
           COALESCE(NULLIF(btrim(regexp_replace(h.note, '^Information requested:\s*', '')), ''),
                    '(legacy request, see history)') AS q
    FROM work_orders w
    JOIN LATERAL (
      SELECT th.actor_id, th.created_at, th.note
      FROM ticket_history th
      WHERE th.work_order_id = w.id AND th.stage = 'info_requested'
        AND th.note LIKE 'Information requested:%'
      ORDER BY th.created_at DESC LIMIT 1
    ) h ON TRUE
    WHERE w.current_stage = 'info_requested' AND w.deleted_at IS NULL AND w.tenant_id IS NOT NULL
  ), ins AS (
    INSERT INTO work_order_info_requests (tenant_id, work_order_id, requested_by_id, question, requested_at)
    SELECT tenant_id, wo_id, actor_id, q, req_at FROM legacy
    ON CONFLICT DO NOTHING
    RETURNING 1
  )
  SELECT count(*) INTO inserted FROM ins;

  SELECT count(*) INTO skipped
  FROM work_orders w
  WHERE w.current_stage = 'info_requested' AND w.deleted_at IS NULL
    AND NOT EXISTS (SELECT 1 FROM work_order_info_requests r
                    WHERE r.work_order_id = w.id AND r.answered_at IS NULL AND r.cancelled_at IS NULL);

  RAISE NOTICE 'V20 info requests: % legacy open request(s) recorded, % ticket(s) skipped (no tenant or no verified history row); SLA not paused retroactively',
    inserted, skipped;
END $$;
