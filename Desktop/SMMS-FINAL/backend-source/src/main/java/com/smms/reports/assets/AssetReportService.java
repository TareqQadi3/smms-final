package com.smms.reports.assets;

import com.smms.entity.User;
import com.smms.exception.WorkflowException;
import com.smms.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Asset and PM reports (read-only SQL aggregates, tenant and location scoped). Reader roles:
 * system_admin (tenant), region_supervisor (region), hospital/maintenance/electrical/operating
 * company managers (own center); everybody else is refused. A centerId outside the scope simply
 * matches nothing. Ranges are capped at 366 days.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AssetReportService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Riyadh");
    static final int MAX_DAYS = 366;

    private final JdbcTemplate jdbc;

    public record Count(String key, long count) {}
    public record DowntimeRow(UUID assetId, String assetCode, String assetName, long periods, double hours) {}
    public record AssetSummary(LocalDate from, LocalDate to, long totalAssets, long decommissioned,
                               List<Count> byStatus, List<Count> byCategory, List<Count> byCriticality,
                               double downtimeHours, List<DowntimeRow> downtimeByAsset) {}
    public record PmRow(UUID planId, String planTitle, UUID assetId, String assetCode, String assetName,
                        java.sql.Date dueDate, String runStatus, String ticketNumber, String workOrderStatus,
                        Instant completedAt, String outcome) {}
    public record PmCompliance(LocalDate from, LocalDate to, long due, long generated, long failed, long skipped,
                               long completedOnTime, long completedLate, long overdue, long open,
                               long dueNotGenerated, List<PmRow> rows) {}

    private record Scope(String where, MapSqlParameterSource params) {}

    /** SQL predicate over aliases p (tenant_id, center_id) and c (centers). */
    private Scope scope(User actor, UUID centerId, String tenantCol, String centerCol, String centerAlias) {
        if (actor == null || actor.getRole() == null) throw new AccessDeniedException("Not allowed");
        StringBuilder w = new StringBuilder();
        MapSqlParameterSource p = new MapSqlParameterSource();
        UUID tenant = TenantContext.get();
        if (tenant != null) { w.append(" AND ").append(tenantCol).append(" = :tenant"); p.addValue("tenant", tenant); }
        UUID center = actor.getCenter() != null ? actor.getCenter().getId() : null;
        UUID region = actor.getRegion() != null ? actor.getRegion().getId() : null;
        switch (actor.getRole()) {
            case system_admin -> { }
            case region_supervisor -> {
                if (region == null) w.append(" AND FALSE");
                else { w.append(" AND ").append(centerAlias).append(".region_id = :region"); p.addValue("region", region); }
            }
            case hospital_supervisor, maintenance_manager, electrical_manager, operating_company_manager -> {
                if (center == null) w.append(" AND FALSE");
                else { w.append(" AND ").append(centerCol).append(" = :scopeCenter"); p.addValue("scopeCenter", center); }
            }
            default -> throw new AccessDeniedException("Your role may not read asset reports");
        }
        if (centerId != null) { w.append(" AND ").append(centerCol).append(" = :centerFilter"); p.addValue("centerFilter", centerId); }
        return new Scope(w.toString(), p);
    }

    static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw new IllegalArgumentException("from and to are required");
        if (to.isBefore(from)) throw new IllegalArgumentException("to cannot be before from");
        if (from.plusDays(MAX_DAYS).isBefore(to)) throw new IllegalArgumentException("The range is limited to " + MAX_DAYS + " days");
    }

    public AssetSummary summary(LocalDate from, LocalDate to, UUID centerId, User actor) {
        validateRange(from, to);
        NamedParameterJdbcTemplate t = new NamedParameterJdbcTemplate(jdbc);
        Scope s = scope(actor, centerId, "a.tenant_id", "a.center_id", "c");
        String base = " FROM assets a JOIN centers c ON c.id = a.center_id WHERE a.deleted_at IS NULL" + s.where();
        long total = t.queryForObject("SELECT count(*)" + base, s.params(), Long.class);
        long decom = t.queryForObject("SELECT count(*)" + base + " AND a.decommissioned_at IS NOT NULL", s.params(), Long.class);
        String live = base + " AND a.decommissioned_at IS NULL";
        List<Count> byStatus = counts(t, "SELECT a.status::text, count(*)" + live + " GROUP BY 1 ORDER BY 1", s);
        List<Count> byCategory = counts(t, "SELECT COALESCE(a.category, ''), count(*)" + live + " GROUP BY 1 ORDER BY 1", s);
        List<Count> byCrit = counts(t, "SELECT COALESCE(a.criticality, ''), count(*)" + live + " GROUP BY 1 ORDER BY 1", s);

        Instant fromI = from.atStartOfDay(ZONE).toInstant();
        Instant toI = to.plusDays(1).atStartOfDay(ZONE).toInstant();
        Instant now = Instant.now();
        MapSqlParameterSource dp = new MapSqlParameterSource(s.params().getValues());
        dp.addValue("from", Timestamp.from(fromI));
        dp.addValue("to", Timestamp.from(toI));
        dp.addValue("now", Timestamp.from(now));
        List<DowntimeRow> rows = t.query("SELECT a.id, a.asset_code, a.name, count(*) AS periods, "
            + "sum(EXTRACT(EPOCH FROM (LEAST(COALESCE(d.ended_at, :now), :to) - GREATEST(d.started_at, :from)))) / 3600.0 AS hours "
            + "FROM asset_downtime d JOIN assets a ON a.id = d.asset_id AND a.tenant_id = d.tenant_id "
            + "JOIN centers c ON c.id = a.center_id WHERE a.deleted_at IS NULL" + s.where()
            + " AND d.started_at < :to AND COALESCE(d.ended_at, :now) > :from "
            + "GROUP BY a.id, a.asset_code, a.name ORDER BY hours DESC, a.name LIMIT 100", dp,
            (rs, n) -> new DowntimeRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getLong(4), Math.round(rs.getDouble(5) * 100.0) / 100.0));
        double hours = Math.round(rows.stream().mapToDouble(DowntimeRow::hours).sum() * 100.0) / 100.0;
        return new AssetSummary(from, to, total, decom, byStatus, byCategory, byCrit, hours, rows);
    }

    private static List<Count> counts(NamedParameterJdbcTemplate t, String sql, Scope s) {
        return t.query(sql, s.params(), (rs, n) -> new Count(rs.getString(1), rs.getLong(2)));
    }

    public PmCompliance compliance(LocalDate from, LocalDate to, UUID centerId, User actor) {
        validateRange(from, to);
        NamedParameterJdbcTemplate t = new NamedParameterJdbcTemplate(jdbc);
        Scope s = scope(actor, centerId, "p.tenant_id", "p.center_id", "c");
        LocalDate today = LocalDate.now(ZONE);
        MapSqlParameterSource p = new MapSqlParameterSource(s.params().getValues());
        p.addValue("from", java.sql.Date.valueOf(from));
        p.addValue("to", java.sql.Date.valueOf(to));
        p.addValue("today", java.sql.Date.valueOf(today));
        p.addValue("zone", ZONE.getId());
        // outcome: completed on time = work order completed by the end of its due day (Riyadh)
        String sql = "SELECT p.id, p.title, a.id, a.asset_code, a.name, r.due_date, r.status, w.ticket_number, "
            + "w.status::text, w.completed_date, CASE "
            + " WHEN r.status = 'failed' THEN 'failed' WHEN r.status = 'skipped' THEN 'skipped' "
            + " WHEN w.completed_date IS NOT NULL AND w.completed_date < ((r.due_date + 1)::timestamp AT TIME ZONE :zone) THEN 'completed_on_time' "
            + " WHEN w.completed_date IS NOT NULL THEN 'completed_late' "
            + " WHEN w.status::text = 'cancelled' THEN 'cancelled' "
            + " WHEN r.due_date < :today THEN 'overdue' ELSE 'open' END AS outcome "
            + "FROM maintenance_plan_runs r JOIN maintenance_plans p ON p.id = r.plan_id AND p.tenant_id = r.tenant_id "
            + "JOIN assets a ON a.id = p.asset_id JOIN centers c ON c.id = p.center_id "
            + "LEFT JOIN work_orders w ON w.id = r.work_order_id "
            + "WHERE r.due_date BETWEEN :from AND :to AND p.deleted_at IS NULL" + s.where()
            + " ORDER BY r.due_date, p.title, r.id LIMIT 50001";
        List<PmRow> rows = t.query(sql, p, (rs, n) -> new PmRow(rs.getObject(1, UUID.class), rs.getString(2),
            rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getDate(6), rs.getString(7),
            rs.getString(8), rs.getString(9),
            rs.getTimestamp(10) != null ? rs.getTimestamp(10).toInstant() : null, rs.getString(11)));
        if (rows.size() > 50_000) throw new WorkflowException("Report has more than 50000 rows; narrow the date range");
        Map<String, Long> by = new HashMap<>();
        for (PmRow r : rows) by.merge(r.outcome(), 1L, Long::sum);
        long generated = rows.stream().filter(r -> !r.runStatus().equals("failed") && !r.runStatus().equals("skipped")).count();
        // active plans whose due date fell in the range but that produced no run yet (backlog)
        long notGenerated = t.queryForObject("SELECT count(*) FROM maintenance_plans p JOIN centers c ON c.id = p.center_id "
            + "WHERE p.is_active AND p.deleted_at IS NULL AND p.next_due_date BETWEEN :from AND :to "
            + "AND p.next_due_date < :today" + s.where(), p, Long.class);
        return new PmCompliance(from, to, rows.size() + notGenerated, generated, by.getOrDefault("failed", 0L),
            by.getOrDefault("skipped", 0L), by.getOrDefault("completed_on_time", 0L), by.getOrDefault("completed_late", 0L),
            by.getOrDefault("overdue", 0L), by.getOrDefault("open", 0L), notGenerated, rows);
    }
}
