package com.smms.notify;

import com.smms.entity.User;
import com.smms.util.CurrentUser;
import com.smms.util.PageRequests;
import com.smms.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** System-admin view of the delivery log and of the provider configuration (no secrets, no addresses). */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class DeliveryAdminController {

    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    public record DeliveryRow(UUID id, UUID notificationId, UUID userId, String channel, String destinationMasked,
                              String status, int attempts, String lastError, String provider, Instant createdAt,
                              Instant sentAt) {}

    public record DeliveryPage(List<DeliveryRow> content, int page, int size, long totalElements, int totalPages) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final ProviderRegistry providers;

    @GetMapping("/notification-deliveries")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<DeliveryPage> deliveries(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser User actor) {
        Pageable pg = PageRequests.of(page, size, null, Map.of(), Sort.by("createdAt"));
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        UUID tenant = TenantContext.get(); // null = platform admin sees every tenant
        if (tenant != null) { where.append(" AND d.tenant_id = :tenant"); p.addValue("tenant", tenant); }
        if (status != null) {
            try { DeliveryStatus.valueOf(status); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid status"); }
            where.append(" AND d.status = :status"); p.addValue("status", status);
        }
        if (channel != null) {
            try { DeliveryChannel.valueOf(channel); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid channel"); }
            where.append(" AND d.channel = :channel"); p.addValue("channel", channel);
        }
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("from must not be after to");
        if (from != null) { where.append(" AND d.created_at >= :from"); p.addValue("from", java.sql.Timestamp.from(from.atStartOfDay(RIYADH).toInstant())); }
        if (to != null) { where.append(" AND d.created_at < :to"); p.addValue("to", java.sql.Timestamp.from(to.plusDays(1).atStartOfDay(RIYADH).toInstant())); }
        Long total = jdbc.queryForObject("SELECT count(*) FROM notification_deliveries d" + where, p, Long.class);
        p.addValue("limit", pg.getPageSize()).addValue("offset", pg.getOffset());
        List<DeliveryRow> rows = jdbc.query("""
            SELECT d.id, d.notification_id, d.user_id, d.channel, d.destination_masked, d.status, d.attempts,
                   d.last_error, d.provider, d.created_at, d.sent_at
              FROM notification_deliveries d""" + where + " ORDER BY d.created_at DESC, d.id LIMIT :limit OFFSET :offset", p,
            (rs, i) -> new DeliveryRow(rs.getObject("id", UUID.class), rs.getObject("notification_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("channel"), rs.getString("destination_masked"),
                rs.getString("status"), rs.getInt("attempts"), rs.getString("last_error"), rs.getString("provider"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant()));
        long t = total == null ? 0 : total;
        return ResponseEntity.ok(new DeliveryPage(rows, pg.getPageNumber(), pg.getPageSize(), t,
            (int) ((t + pg.getPageSize() - 1) / pg.getPageSize())));
    }

    @GetMapping("/notification-providers")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<List<ProviderStatus>> providers() {
        return ResponseEntity.ok(providers.statuses());
    }
}
