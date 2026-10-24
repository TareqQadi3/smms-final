package com.smms.reports;

import com.smms.reports.ReportModels.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Read-only aggregate SQL of the operational reports, over persisted data only. Every query is
 * scoped through {@link ReportScope#sql} (tenant, center, region, soft delete). Definitions:
 * <ul>
 *   <li>finished = status completed or closed; finish time = completed_date (closed_at when a closed
 *       ticket has no completed_date).</li>
 *   <li>turnaround hours = (finish - created_at) minus sla_paused_seconds, never below 0.</li>
 *   <li>SLA cohort = non-cancelled tickets whose sla_deadline falls in the period. met = finished at or
 *       before the deadline; breached = finished after it, plus still open past the deadline with the
 *       clock not paused (openBreached). Open tickets not yet due are not counted in either.</li>
 * </ul>
 * Reads run in READ ONLY transactions, so a report never writes.
 */
@Repository
@Transactional(readOnly = true)
public class ReportQueries {

    private static final String FIN = "COALESCE(w.completed_date, CASE WHEN w.status::text = 'closed' THEN w.closed_at END)";
    private static final String DONE = "w.status::text IN ('completed','closed')";
    private static final String TERMINAL = "('completed','closed','cancelled')";
    private static final String HOURS = "GREATEST(0, EXTRACT(EPOCH FROM (" + FIN + " - w.created_at)) - w.sla_paused_seconds) / 3600.0";
    private static final String TZ = "'Asia/Riyadh'";
    static final List<String> PRIORITIES = List.of("critical", "high", "medium", "low");
    public static final int CSV_FETCH_LIMIT = 50_001;

    private final NamedParameterJdbcTemplate jdbc;

    public ReportQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp ts(Instant i) { return Timestamp.from(i); }

    private static MapSqlParameterSource rangeParams(ReportRange r) {
        return new MapSqlParameterSource().addValue("start", ts(r.start())).addValue("end", ts(r.endExclusive()));
    }

    private static BigDecimal scale2(BigDecimal v) { return v == null ? null : v.setScale(2, RoundingMode.HALF_UP); }

    public Optional<ReportScope.CenterInfo> center(UUID id) {
        return jdbc.query("SELECT tenant_id, region_id FROM centers WHERE id = :id", new MapSqlParameterSource("id", id),
            (rs, i) -> new ReportScope.CenterInfo(rs.getObject("tenant_id", UUID.class), rs.getObject("region_id", UUID.class)))
            .stream().findFirst();
    }

    // ---- volume ------------------------------------------------------------------------------

    public static final Set<String> VOLUME_GROUPS = Set.of("status", "priority", "subType", "controlRoom", "day", "week");

    public List<Group> volume(ReportRange range, ReportScope scope, String groupBy) {
        String key, label, join = "", order = "count(*) DESC, key";
        switch (groupBy) {
            case "status" -> { key = "w.status::text"; label = key; }
            case "priority" -> { key = "w.priority::text"; label = key; }
            case "subType" -> { key = "w.sub_type::text"; label = key; }
            case "controlRoom" -> {
                key = "w.control_room_id::text"; label = "COALESCE(cr.name, w.control_room_id::text)";
                join = " LEFT JOIN control_rooms cr ON cr.id = w.control_room_id";
            }
            case "day" -> { key = "to_char(date_trunc('day', w.created_at AT TIME ZONE " + TZ + "), 'YYYY-MM-DD')"; label = key; order = "key"; }
            case "week" -> { key = "to_char(date_trunc('week', w.created_at AT TIME ZONE " + TZ + "), 'YYYY-MM-DD')"; label = key; order = "key"; }
            default -> throw new IllegalArgumentException("Unsupported groupBy");
        }
        MapSqlParameterSource p = rangeParams(range);
        String sql = "SELECT " + key + " AS key, " + label + " AS label, count(*) AS n FROM work_orders w"
            + " JOIN centers c ON c.id = w.center_id" + join
            + " WHERE w.created_at >= :start AND w.created_at < :end" + scope.sql("w", "c", p)
            + " GROUP BY key, label ORDER BY " + order;
        return jdbc.query(sql, p, (rs, i) -> new Group(rs.getString("key"), rs.getString("label"), rs.getLong("n")));
    }

    // ---- turnaround --------------------------------------------------------------------------

    public static final Set<String> TURNAROUND_GROUPS = Set.of("priority", "subType");

    public List<Turnaround> turnaround(ReportRange range, ReportScope scope, String groupBy) {
        String key = switch (groupBy) {
            case "priority" -> "w.priority::text";
            case "subType" -> "w.sub_type::text";
            default -> throw new IllegalArgumentException("Unsupported groupBy");
        };
        MapSqlParameterSource p = rangeParams(range);
        String sql = "WITH f AS (SELECT " + key + " AS k, (" + HOURS + ")::float8 AS h FROM work_orders w"
            + " JOIN centers c ON c.id = w.center_id"
            + " WHERE " + DONE + " AND " + FIN + " >= :start AND " + FIN + " < :end" + scope.sql("w", "c", p) + ")"
            + " SELECT k, count(*) AS n, round(avg(h)::numeric, 2) AS a,"
            + " round((percentile_cont(0.5) WITHIN GROUP (ORDER BY h))::numeric, 2) AS p50,"
            + " round((percentile_cont(0.9) WITHIN GROUP (ORDER BY h))::numeric, 2) AS p90"
            + " FROM f GROUP BY k ORDER BY k";
        return jdbc.query(sql, p, (rs, i) -> new Turnaround(rs.getString("k"), rs.getLong("n"),
            rs.getBigDecimal("a"), rs.getBigDecimal("p50"), rs.getBigDecimal("p90")));
    }

    // ---- SLA ---------------------------------------------------------------------------------

    public List<SlaRow> sla(ReportRange range, ReportScope scope, Instant now) {
        MapSqlParameterSource p = rangeParams(range).addValue("now", ts(now));
        String sql = "SELECT w.priority::text AS prio,"
            + " count(*) FILTER (WHERE " + FIN + " IS NOT NULL AND " + FIN + " <= w.sla_deadline) AS met,"
            + " count(*) FILTER (WHERE " + FIN + " IS NOT NULL AND " + FIN + " > w.sla_deadline) AS late,"
            + " count(*) FILTER (WHERE " + FIN + " IS NULL AND w.status::text NOT IN " + TERMINAL
            + " AND w.sla_paused_at IS NULL AND w.sla_deadline < :now) AS open_breached,"
            + " avg(w.sla_paused_seconds) / 3600.0 AS paused_h"
            + " FROM work_orders w JOIN centers c ON c.id = w.center_id"
            + " WHERE w.sla_deadline >= :start AND w.sla_deadline < :end AND w.status::text <> 'cancelled'"
            + scope.sql("w", "c", p) + " GROUP BY w.priority";
        Map<String, SlaRow> byPriority = new HashMap<>();
        jdbc.query(sql, p, rs -> {
            long met = rs.getLong("met"), late = rs.getLong("late"), open = rs.getLong("open_breached");
            long due = met + late + open;
            BigDecimal pct = due == 0 ? null
                : BigDecimal.valueOf(met * 100.0 / due).setScale(2, RoundingMode.HALF_UP);
            byPriority.put(rs.getString("prio"), new SlaRow(rs.getString("prio"), due, met, late + open, pct,
                scale2(rs.getBigDecimal("paused_h")), open));
        });
        List<SlaRow> out = new ArrayList<>();
        for (String prio : PRIORITIES) {
            out.add(byPriority.getOrDefault(prio, new SlaRow(prio, 0, 0, 0, null, null, 0)));
        }
        return out;
    }

    // ---- escalations (table arrives with another workstream) ----------------------------------

    public boolean escalationsAvailable() {
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass('public.work_order_escalations') IS NOT NULL", new MapSqlParameterSource(), Boolean.class));
        } catch (DataAccessException e) {
            return false;
        }
    }

    /** Empty optional when the table is missing or does not have the expected columns. */
    public Optional<EscalationReport> escalations(ReportRange range, ReportScope scope) {
        if (!escalationsAvailable()) return Optional.empty();
        MapSqlParameterSource p = rangeParams(range);
        String from = " FROM work_order_escalations e JOIN work_orders w ON w.id = e.work_order_id"
            + " JOIN centers c ON c.id = w.center_id WHERE e.created_at >= :start AND e.created_at < :end"
            + scope.sql("w", "c", p);
        try {
            List<EscalationGroup> groups = jdbc.query("SELECT e.level AS lvl, e.\"trigger\" AS trg, count(*) AS n" + from
                + " GROUP BY e.level, e.\"trigger\" ORDER BY e.level, e.\"trigger\"", p,
                (rs, i) -> new EscalationGroup(rs.getInt("lvl"), rs.getString("trg"), rs.getLong("n")));
            List<DayCount> days = jdbc.query("SELECT to_char(date_trunc('day', e.created_at AT TIME ZONE " + TZ
                + "), 'YYYY-MM-DD') AS d, count(*) AS n" + from + " GROUP BY d ORDER BY d", p,
                (rs, i) -> new DayCount(rs.getString("d"), rs.getLong("n")));
            return Optional.of(new EscalationReport(range.from(), range.to(), true, groups, days));
        } catch (DataAccessException e) {
            return Optional.empty();
        }
    }

    // ---- technicians -------------------------------------------------------------------------

    public Page<TechnicianRow> technicians(ReportRange range, ReportScope scope, int page, int size, boolean unpaged) {
        MapSqlParameterSource p = rangeParams(range);
        String woScope = scope.sql("w", "c", p);
        String inRange = FIN + " >= :start AND " + FIN + " < :end";
        String created = "w.created_at >= :start AND w.created_at < :end";
        StringBuilder userWhere = new StringBuilder(
            " WHERE u.role::text = 'technician' AND u.deleted_at IS NULL");
        if (scope.tenantId() != null) { userWhere.append(" AND u.tenant_id = :sc_tenant"); }
        if (scope.centerId() != null) { userWhere.append(" AND u.center_id = :sc_center"); }
        if (scope.regionId() != null) { userWhere.append(" AND uc.region_id = :sc_region"); }
        userWhere.append(" AND (u.is_active IS NOT FALSE OR a.uid IS NOT NULL)");
        String base = " FROM users u LEFT JOIN centers uc ON uc.id = u.center_id LEFT JOIN agg a ON a.uid = u.id" + userWhere;
        String with = "WITH agg AS (SELECT w.assigned_to_id AS uid,"
            + " count(*) FILTER (WHERE " + created + ") AS assigned,"
            + " count(*) FILTER (WHERE " + DONE + " AND " + inRange + ") AS completed,"
            + " count(*) FILTER (WHERE w.status::text NOT IN " + TERMINAL + ") AS open,"
            + " count(*) FILTER (WHERE " + created + " AND w.reopened_count > 0) AS reopened,"
            + " avg(" + HOURS + ") FILTER (WHERE " + DONE + " AND " + inRange + ") AS avg_h,"
            + " count(*) FILTER (WHERE " + DONE + " AND " + inRange + " AND w.priority::text = 'critical') AS c_crit,"
            + " count(*) FILTER (WHERE " + DONE + " AND " + inRange + " AND w.priority::text = 'high') AS c_high,"
            + " count(*) FILTER (WHERE " + DONE + " AND " + inRange + " AND w.priority::text = 'medium') AS c_med,"
            + " count(*) FILTER (WHERE " + DONE + " AND " + inRange + " AND w.priority::text = 'low') AS c_low,"
            + " count(*) FILTER (WHERE " + DONE + " AND " + inRange + " AND w.is_emergency) AS emerg"
            + " FROM work_orders w JOIN centers c ON c.id = w.center_id"
            + " WHERE w.assigned_to_id IS NOT NULL" + woScope
            + " AND (" + created + " OR (" + FIN + " >= :start AND " + FIN + " < :end) OR w.status::text NOT IN " + TERMINAL + ")"
            + " GROUP BY w.assigned_to_id)";
        Long total = jdbc.queryForObject(with + " SELECT count(*)" + base, p, Long.class);
        String select = with + " SELECT u.id, u.full_name, COALESCE(a.assigned,0) AS assigned, COALESCE(a.completed,0) AS completed,"
            + " COALESCE(a.open,0) AS open, COALESCE(a.reopened,0) AS reopened, a.avg_h,"
            + " COALESCE(a.c_crit,0) AS c_crit, COALESCE(a.c_high,0) AS c_high, COALESCE(a.c_med,0) AS c_med,"
            + " COALESCE(a.c_low,0) AS c_low, COALESCE(a.emerg,0) AS emerg" + base
            + " ORDER BY u.full_name, u.id";
        if (unpaged) {
            select += " LIMIT " + CSV_FETCH_LIMIT;
        } else {
            select += " LIMIT :limit OFFSET :offset";
            p.addValue("limit", size).addValue("offset", (long) page * size);
        }
        List<TechnicianRow> rows = jdbc.query(select, p, (rs, i) -> new TechnicianRow(
            new UserRef(rs.getObject("id", UUID.class), rs.getString("full_name")),
            rs.getLong("assigned"), rs.getLong("completed"), rs.getLong("open"), rs.getLong("reopened"),
            scale2(rs.getBigDecimal("avg_h")),
            new ByPriority(rs.getLong("c_crit"), rs.getLong("c_high"), rs.getLong("c_med"), rs.getLong("c_low")),
            rs.getLong("emerg")));
        long t = total == null ? 0 : total;
        int sz = unpaged ? Math.max(rows.size(), 1) : size;
        return new Page<>(rows, unpaged ? 0 : page, sz, t, (int) ((t + sz - 1) / sz));
    }

    // ---- overdue -----------------------------------------------------------------------------

    public Page<OverdueRow> overdue(ReportScope scope, Instant now, String priority, int page, int size, boolean unpaged) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("now", ts(now));
        String where = " FROM work_orders w JOIN centers c ON c.id = w.center_id LEFT JOIN users u ON u.id = w.assigned_to_id"
            + " WHERE w.status::text NOT IN " + TERMINAL + " AND w.sla_paused_at IS NULL AND w.sla_deadline IS NOT NULL"
            + " AND w.sla_deadline < :now" + scope.sql("w", "c", p);
        if (priority != null) {
            if (!PRIORITIES.contains(priority)) throw new IllegalArgumentException("Invalid priority");
            where += " AND w.priority::text = :prio";
            p.addValue("prio", priority);
        }
        Long total = jdbc.queryForObject("SELECT count(*)" + where, p, Long.class);
        String sql = "SELECT w.id, w.ticket_number, w.title, w.priority::text AS prio, w.status::text AS st, w.center_id,"
            + " c.name AS center_name, u.id AS uid, u.full_name AS uname, w.sla_deadline,"
            + " round((EXTRACT(EPOCH FROM (CAST(:now AS timestamptz) - w.sla_deadline)) / 3600.0)::numeric, 2) AS over_h" + where
            + " ORDER BY w.sla_deadline, w.id";
        if (unpaged) {
            sql += " LIMIT " + CSV_FETCH_LIMIT;
        } else {
            sql += " LIMIT :limit OFFSET :offset";
            p.addValue("limit", size).addValue("offset", (long) page * size);
        }
        List<OverdueRow> rows = jdbc.query(sql, p, (rs, i) -> new OverdueRow(rs.getObject("id", UUID.class),
            rs.getString("ticket_number"), rs.getString("title"), rs.getString("prio"), rs.getString("st"),
            rs.getObject("center_id", UUID.class), rs.getString("center_name"),
            rs.getObject("uid", UUID.class) == null ? null : new UserRef(rs.getObject("uid", UUID.class), rs.getString("uname")),
            rs.getTimestamp("sla_deadline").toInstant(), rs.getBigDecimal("over_h")));
        long t = total == null ? 0 : total;
        int sz = unpaged ? Math.max(rows.size(), 1) : size;
        return new Page<>(rows, unpaged ? 0 : page, sz, t, (int) ((t + sz - 1) / sz));
    }
}
