package com.smms.notify;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcDeliveryStore implements DeliveryStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDeliveryStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp ts(Instant i) { return Timestamp.from(i); }

    @Override
    @Transactional
    public List<Claimed> claimDue(Instant now, Instant leaseUntil, int limit) {
        // The lease update and the selection are one statement; SKIP LOCKED lets concurrent runs
        // take disjoint rows. The user and notification are joined in the same tenant only.
        String sql = """
            WITH due AS (
              SELECT id FROM notification_deliveries
               WHERE status IN ('pending','failed') AND next_attempt_at <= :now
               ORDER BY next_attempt_at, id
               LIMIT :limit
               FOR UPDATE SKIP LOCKED
            ), leased AS (
              UPDATE notification_deliveries d SET next_attempt_at = :lease
                FROM due WHERE d.id = due.id
              RETURNING d.id, d.tenant_id, d.channel, d.attempts, d.user_id, d.notification_id
            )
            SELECT l.id, l.tenant_id, l.channel, l.attempts,
                   CASE WHEN l.channel = 'email' THEN u.email ELSE u.phone END AS destination,
                   n.title, n.body
              FROM leased l
              JOIN users u ON u.id = l.user_id AND u.tenant_id = l.tenant_id AND u.deleted_at IS NULL
              JOIN notifications n ON n.id = l.notification_id AND n.tenant_id = l.tenant_id
            """;
        return jdbc.query(sql, new MapSqlParameterSource()
                .addValue("now", ts(now)).addValue("lease", ts(leaseUntil)).addValue("limit", limit),
            (rs, i) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                DeliveryChannel.fromDb(rs.getString("channel")), rs.getInt("attempts"),
                rs.getString("destination"), rs.getString("title"), rs.getString("body")));
    }

    @Override
    public void markSent(UUID id, String provider, String providerMessageId, Instant now) {
        jdbc.update("""
            UPDATE notification_deliveries SET status='sent', attempts=attempts+1, sent_at=:now,
                   provider=:p, provider_message_id=:mid, last_error=NULL WHERE id=:id
            """, new MapSqlParameterSource().addValue("now", ts(now)).addValue("p", provider)
                .addValue("mid", providerMessageId).addValue("id", id));
    }

    @Override
    public void markFailed(UUID id, String provider, String error, Instant nextAttemptAt, boolean dead) {
        jdbc.update("""
            UPDATE notification_deliveries SET status=:st, attempts=attempts+1, provider=:p,
                   last_error=:err, next_attempt_at=COALESCE(:next, next_attempt_at) WHERE id=:id
            """, new MapSqlParameterSource().addValue("st", dead ? "dead" : "failed").addValue("p", provider)
                .addValue("err", error).addValue("next", nextAttemptAt == null ? null : ts(nextAttemptAt))
                .addValue("id", id));
    }

    @Override
    public void markTerminal(UUID id, DeliveryStatus status, String provider, String reason) {
        if (status.dispatchable() || status == DeliveryStatus.sent) {
            throw new IllegalArgumentException("not a terminal no-attempt status: " + status);
        }
        jdbc.update("UPDATE notification_deliveries SET status=:st, provider=:p, last_error=:err WHERE id=:id",
            new MapSqlParameterSource().addValue("st", status.name()).addValue("p", provider)
                .addValue("err", reason).addValue("id", id));
    }

    /**
     * One INSERT..SELECT per channel. Rules: the user opted in (and the channel was enabled before the
     * notification), the notification type passes the user's filter, the recipient is active and in the
     * same tenant as the notification, nothing is older than {@code notBefore}. UNIQUE
     * (notification_id, channel) makes reruns and parallel runs harmless. A channel without a usable
     * address is recorded as skipped_no_address instead of pending.
     */
    @Override
    @Transactional
    public int enqueueNew(Instant now, Instant notBefore) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("now", ts(now)).addValue("nb", ts(notBefore));
        int email = jdbc.update("""
            INSERT INTO notification_deliveries (tenant_id, notification_id, user_id, channel, destination_masked, status, next_attempt_at)
            SELECT n.tenant_id, n.id, n.user_id, 'email',
                   CASE WHEN position('@' in u.email) > 1
                        THEN substr(u.email, 1, 1) || '***' || substr(u.email, position('@' in u.email)) END,
                   CASE WHEN position('@' in u.email) > 1 THEN 'pending' ELSE 'skipped_no_address' END, :now
              FROM notifications n
              JOIN notification_preferences pr ON pr.user_id = n.user_id AND pr.tenant_id = n.tenant_id
              JOIN users u ON u.id = n.user_id AND u.tenant_id = n.tenant_id
             WHERE pr.email_enabled AND pr.email_enabled_at IS NOT NULL
               AND n.created_at >= GREATEST(pr.email_enabled_at, :nb)
               AND (pr.types IS NULL OR n.type = ANY(pr.types))
               AND u.deleted_at IS NULL AND u.is_active IS NOT FALSE AND n.tenant_id IS NOT NULL
            ON CONFLICT (notification_id, channel) DO NOTHING
            """, p);
        int sms = jdbc.update("""
            INSERT INTO notification_deliveries (tenant_id, notification_id, user_id, channel, destination_masked, status, next_attempt_at)
            SELECT n.tenant_id, n.id, n.user_id, 'sms',
                   CASE WHEN length(btrim(coalesce(u.phone,''))) >= 7 THEN '***' || right(btrim(u.phone), 3) END,
                   CASE WHEN length(btrim(coalesce(u.phone,''))) >= 7 THEN 'pending' ELSE 'skipped_no_address' END, :now
              FROM notifications n
              JOIN notification_preferences pr ON pr.user_id = n.user_id AND pr.tenant_id = n.tenant_id
              JOIN users u ON u.id = n.user_id AND u.tenant_id = n.tenant_id
             WHERE pr.sms_enabled AND pr.sms_enabled_at IS NOT NULL
               AND n.created_at >= GREATEST(pr.sms_enabled_at, :nb)
               AND (pr.types IS NULL OR n.type = ANY(pr.types))
               AND u.deleted_at IS NULL AND u.is_active IS NOT FALSE AND n.tenant_id IS NOT NULL
            ON CONFLICT (notification_id, channel) DO NOTHING
            """, p);
        return email + sms;
    }
}
