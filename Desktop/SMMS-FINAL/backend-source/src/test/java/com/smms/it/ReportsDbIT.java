package com.smms.it;

import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.reports.*;
import com.smms.reports.ReportModels.*;
import com.smms.support.Fixtures;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Known-answer test of the operational report SQL on a real PostgreSQL: two tenants, two centers in
 * tenant A, boundary timestamps, a soft-deleted ticket, paused SLA time, and scope enforcement. The
 * expected numbers are worked out by hand in the comments next to the fixture rows.
 */
@TestPropertySource(properties = "app.notifications.scheduler-enabled=false")
class ReportsDbIT extends AbstractDbIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired ReportQueries queries;
    @Autowired ReportService service;

    private static final Instant NOW = Instant.parse("2026-04-01T00:00:00Z");
    private final ReportRange range = new ReportRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

    // fixture ids (filled by data())
    private DbFixtures.Hierarchy a1, a2, b1;
    private User saA, saB, hsA1, hsB, rsA, tech;
    private UUID t1, t2, t3, t5, tb;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private UUID center2(DbFixtures.Hierarchy h) {
        UUID c = UUID.randomUUID(), room = UUID.randomUUID();
        jdbc.update("INSERT INTO centers (id, tenant_id, region_id, name, name_ar, code) VALUES (?, ?, ?, 'C2', 'C2', ?)",
            c, h.tenant(), h.region(), "C" + c.toString().substring(0, 8));
        jdbc.update("INSERT INTO control_rooms (id, tenant_id, center_id, type, name, name_ar) VALUES (?, ?, ?, 'hospital'::control_room_type, 'CR2', 'CR2')",
            room, h.tenant(), c);
        return c;
    }

    private UUID wo(DbFixtures f, DbFixtures.Hierarchy h, UUID requester, String status, String priority,
                    String created, String completed, String deadline, long pausedSeconds, UUID assignee) {
        UUID id = f.workOrder(h, requester, status, priority);
        jdbc.update("UPDATE work_orders SET created_at = ?::timestamptz, completed_date = ?::timestamptz, "
                + "sla_deadline = ?::timestamptz, sla_paused_seconds = ?, assigned_to_id = ? WHERE id = ?",
            created, completed, deadline, pausedSeconds, assignee, id);
        return id;
    }

    private void data() {
        DbFixtures f = new DbFixtures(jdbc);
        a1 = f.hierarchy();
        a2 = new DbFixtures.Hierarchy(a1.tenant(), a1.region(), center2(a1), null);
        UUID room2 = jdbc.queryForObject("SELECT id FROM control_rooms WHERE center_id = ?", UUID.class, a2.center());
        a2 = new DbFixtures.Hierarchy(a1.tenant(), a1.region(), a2.center(), room2);
        b1 = f.hierarchy();
        UUID reqA = f.user(a1, "control_room"), reqA2 = f.user(a2, "control_room"), reqB = f.user(b1, "control_room");
        t1 = f.user(a1, "technician"); t2 = f.user(a1, "technician"); t3 = f.user(a2, "technician");
        t5 = f.user(a1, "technician"); tb = f.user(b1, "technician");
        UUID t4 = f.user(a1, "technician");
        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", t4);
        Map<UUID, String> names = Map.of(t1, "A-T1", t2, "A-T2", t3, "A-T3", t5, "A-T5", tb, "B-T1", t4, "A-T4-inactive");
        names.forEach((id, n) -> jdbc.update("UPDATE users SET full_name = ? WHERE id = ?", n, id));

        // ---- tenant A, center A1 ----
        wo(f, a1, reqA, "completed", "critical", "2026-03-05T08:00:00Z", "2026-03-05T12:00:00Z", "2026-03-05T20:00:00Z", 0, t1);   // 4h, met
        wo(f, a1, reqA, "completed", "high", "2026-03-06T00:00:00Z", "2026-03-06T10:00:00Z", "2026-03-06T09:00:00Z", 7200, t1);    // 10h-2h=8h, late
        UUID w3 = wo(f, a1, reqA, "closed", "high", "2026-03-10T06:00:00Z", "2026-03-10T12:00:00Z", "2026-03-10T18:00:00Z", 0, t2); // 6h, met
        jdbc.update("UPDATE work_orders SET is_emergency = true WHERE id = ?", w3);
        UUID w4 = wo(f, a1, reqA, "in_progress", "medium", "2026-03-12T00:00:00Z", null, "2026-03-13T00:00:00Z", 0, t1);          // open, overdue
        jdbc.update("UPDATE work_orders SET reopened_count = 1 WHERE id = ?", w4);
        UUID w5 = wo(f, a1, reqA, "in_progress", "medium", "2026-03-12T00:00:00Z", null, "2026-03-14T00:00:00Z", 3600, t2);        // paused clock
        jdbc.update("UPDATE work_orders SET sla_paused_at = ?::timestamptz WHERE id = ?", "2026-03-13T00:00:00Z", w5);
        wo(f, a1, reqA, "new", "low", "2026-03-20T00:00:00Z", null, "2026-06-01T00:00:00Z", 0, null);                              // W6
        wo(f, a1, reqA, "cancelled", "high", "2026-03-15T00:00:00Z", null, "2026-03-16T00:00:00Z", 0, null);                       // W7
        wo(f, a1, reqA, "new", "low", "2026-02-28T21:00:00Z", null, "2026-06-01T00:00:00Z", 0, null);                              // W8 first instant of range
        wo(f, a1, reqA, "new", "low", "2026-02-28T20:59:59Z", null, "2026-06-01T00:00:00Z", 0, null);                              // W9 just before
        wo(f, a1, reqA, "new", "low", "2026-03-31T20:59:59Z", null, "2026-06-01T00:00:00Z", 0, null);                              // W10 last second
        wo(f, a1, reqA, "new", "low", "2026-03-31T21:00:00Z", null, "2026-06-01T00:00:00Z", 0, null);                              // W11 just after
        UUID w12 = wo(f, a1, reqA, "completed", "critical", "2026-03-05T00:00:00Z", "2026-03-05T01:00:00Z", "2026-03-05T10:00:00Z", 0, t1);
        jdbc.update("UPDATE work_orders SET deleted_at = now() WHERE id = ?", w12);                                               // soft deleted
        // ---- tenant A, center A2 ----
        wo(f, a2, reqA2, "completed", "high", "2026-03-07T00:00:00Z", "2026-03-07T06:00:00Z", "2026-03-07T12:00:00Z", 0, t3);      // 6h, met
        // ---- tenant B ----
        wo(f, b1, reqB, "completed", "critical", "2026-03-01T00:00:00Z", "2026-03-05T04:00:00Z", "2026-03-06T00:00:00Z", 0, tb);   // 100h

        saA = Fixtures.user(UserRole.system_admin, null, null, a1.tenant());
        saB = Fixtures.user(UserRole.system_admin, null, null, b1.tenant());
        hsA1 = Fixtures.user(UserRole.hospital_supervisor, ref(a1.center()), null, a1.tenant());
        hsB = Fixtures.user(UserRole.hospital_supervisor, ref(b1.center()), null, b1.tenant());
        rsA = Fixtures.user(UserRole.region_supervisor, null, regionRef(a1.region()), a1.tenant());
        tech = Fixtures.user(UserRole.technician, ref(a1.center()), null, a1.tenant());
    }

    private com.smms.entity.Center ref(UUID id) {
        com.smms.entity.Center c = new com.smms.entity.Center();
        c.setId(id);
        return c;
    }

    private com.smms.entity.Region regionRef(UUID id) {
        com.smms.entity.Region r = new com.smms.entity.Region();
        r.setId(id);
        return r;
    }

    private Map<String, Long> counts(List<Group> g) {
        return g.stream().collect(Collectors.toMap(Group::key, Group::count));
    }

    private ReportService.Request req(User actor, UUID center) {
        return service.resolve(actor, range.from(), range.to(), center, null);
    }

    @org.junit.jupiter.api.BeforeEach
    void ensureData() {
        data(); // fresh tenants per test: nothing is shared, nothing depends on test order
    }

    @Test
    void volumeByStatusPriorityAndBoundaries() {
        TenantContext.set(a1.tenant());
        Volume byStatus = service.volume(req(saA, null), "status");
        assertThat(counts(byStatus.groups())).containsOnly(
            Map.entry("completed", 3L), Map.entry("closed", 1L), Map.entry("in_progress", 2L),
            Map.entry("new", 3L), Map.entry("cancelled", 1L));
        assertThat(byStatus.total()).isEqualTo(10); // W9, W11 outside the range, W12 soft-deleted
        assertThat(counts(service.volume(req(saA, null), "priority").groups()))
            .containsOnly(Map.entry("critical", 1L), Map.entry("high", 4L), Map.entry("medium", 2L), Map.entry("low", 3L));
        // center narrowing
        assertThat(service.volume(req(saA, a1.center()), "status").total()).isEqualTo(9);
        assertThat(service.volume(req(saA, a2.center()), "status").total()).isEqualTo(1);
        // day buckets are Riyadh days: W8 (2026-02-28T21:00Z) is 2026-03-01 locally
        Map<String, Long> days = counts(service.volume(req(saA, null), "day").groups());
        assertThat(days.get("2026-03-01")).isEqualTo(1L);
        assertThat(days.get("2026-03-31")).isEqualTo(1L);
        assertThat(days.get("2026-03-05")).isEqualTo(1L);
        assertThat(days).doesNotContainKeys("2026-02-28", "2026-04-01");
        assertThat(service.volume(req(saA, null), "day").total()).isEqualTo(10);
        // week buckets start on Monday: 2026-03-02 is a Monday
        assertThat(counts(service.volume(req(saA, null), "week").groups())).containsKey("2026-03-02");
        assertThat(service.volume(req(saA, null), "controlRoom").groups()).hasSize(2);
        assertThat(service.volume(req(saA, null), "subType").groups()).extracting(Group::key).containsExactly("electrical");
    }

    @Test
    void turnaroundIsNetOfPausedTimeWithPercentiles() {
        TenantContext.set(a1.tenant());
        List<Turnaround> all = service.turnaround(req(saA, null), "priority").rows();
        Map<String, Turnaround> by = all.stream().collect(Collectors.toMap(Turnaround::key, t -> t));
        assertThat(by).containsOnlyKeys("critical", "high");
        assertThat(by.get("critical").completedCount()).isEqualTo(1);
        assertThat(by.get("critical").avgHours()).isEqualByComparingTo("4.00");
        Turnaround high = by.get("high"); // 8h (10h - 2h paused), 6h, 6h
        assertThat(high.completedCount()).isEqualTo(3);
        assertThat(high.avgHours()).isEqualByComparingTo("6.67");
        assertThat(high.p50Hours()).isEqualByComparingTo("6.00");
        assertThat(high.p90Hours()).isEqualByComparingTo("7.60");
        Turnaround highA1 = service.turnaround(req(saA, a1.center()), "priority").rows().stream()
            .filter(t -> t.key().equals("high")).findFirst().orElseThrow();
        assertThat(highA1.completedCount()).isEqualTo(2);
        assertThat(highA1.avgHours()).isEqualByComparingTo("7.00");
        assertThat(highA1.p90Hours()).isEqualByComparingTo("7.80");
    }

    @Test
    void slaComplianceIncludingPausedTimeAndOpenBreaches() {
        TenantContext.set(a1.tenant());
        ReportService.Request r = req(saA, null);
        List<SlaRow> rows = queries.sla(r.range(), r.scope(), NOW);
        assertThat(rows).extracting(SlaRow::priority).containsExactly("critical", "high", "medium", "low");
        SlaRow critical = rows.get(0), high = rows.get(1), medium = rows.get(2), low = rows.get(3);
        assertThat(critical.due()).isEqualTo(1);
        assertThat(critical.metOnTime()).isEqualTo(1);
        assertThat(critical.compliancePct()).isEqualByComparingTo("100.00");
        assertThat(high.due()).isEqualTo(3);            // W2 late, W3 met, W13 met; W7 cancelled is excluded
        assertThat(high.metOnTime()).isEqualTo(2);
        assertThat(high.breached()).isEqualTo(1);
        assertThat(high.compliancePct()).isEqualByComparingTo("66.67");
        assertThat(high.avgPausedHours()).isEqualByComparingTo("0.67"); // 7200 s over 3 tickets
        assertThat(high.openBreached()).isZero();
        assertThat(medium.due()).isEqualTo(1);          // W4 overdue and running; W5 is paused so not counted
        assertThat(medium.breached()).isEqualTo(1);
        assertThat(medium.openBreached()).isEqualTo(1);
        assertThat(medium.compliancePct()).isEqualByComparingTo("0.00");
        assertThat(medium.avgPausedHours()).isEqualByComparingTo("0.50"); // (0 + 3600 s) / 2
        assertThat(low.due()).isZero();
        assertThat(low.compliancePct()).isNull();
    }

    @Test
    void technicianMetricsAreRawCountsWithinScope() {
        TenantContext.set(a1.tenant());
        Page<TechnicianRow> p = queries.technicians(range, req(saA, null).scope(), 0, 20, false);
        assertThat(p.totalElements()).isEqualTo(4); // T1, T2, T3, T5; inactive T4 without work and tenant B tech are absent
        assertThat(p.content()).extracting(t -> t.technician().fullName()).containsExactly("A-T1", "A-T2", "A-T3", "A-T5");
        TechnicianRow r1 = p.content().get(0), r2 = p.content().get(1), r3 = p.content().get(2), r5 = p.content().get(3);
        assertThat(r1.assigned()).isEqualTo(3);
        assertThat(r1.completed()).isEqualTo(2);
        assertThat(r1.open()).isEqualTo(1);
        assertThat(r1.reopened()).isEqualTo(1);
        assertThat(r1.avgTurnaroundHours()).isEqualByComparingTo("6.00"); // (4h + 8h) / 2
        assertThat(r1.completedByPriority()).isEqualTo(new ByPriority(1, 1, 0, 0));
        assertThat(r1.emergencyHandled()).isZero();
        assertThat(r2.assigned()).isEqualTo(2);
        assertThat(r2.completed()).isEqualTo(1);
        assertThat(r2.open()).isEqualTo(1);
        assertThat(r2.emergencyHandled()).isEqualTo(1);
        assertThat(r3.completed()).isEqualTo(1);
        assertThat(r5.assigned()).isZero();
        assertThat(r5.avgTurnaroundHours()).isNull();
        // paging
        Page<TechnicianRow> page2 = queries.technicians(range, req(saA, null).scope(), 1, 2, false);
        assertThat(page2.content()).extracting(t -> t.technician().fullName()).containsExactly("A-T3", "A-T5");
        assertThat(page2.totalPages()).isEqualTo(2);
        // center A1 hides the A2 technician and A2 work
        Page<TechnicianRow> c1 = queries.technicians(range, req(saA, a1.center()).scope(), 0, 20, false);
        assertThat(c1.content()).extracting(t -> t.technician().fullName()).containsExactly("A-T1", "A-T2", "A-T5");
    }

    @Test
    void overdueIsASnapshotOfOpenRunningTickets() {
        TenantContext.set(a1.tenant());
        ReportScope scope = req(saA, null).scope();
        Page<OverdueRow> p = queries.overdue(scope, NOW, null, 0, 20, false);
        assertThat(p.totalElements()).isEqualTo(1);
        OverdueRow o = p.content().get(0);
        assertThat(o.priority()).isEqualTo("medium");
        assertThat(o.hoursOverdue()).isEqualByComparingTo(new BigDecimal("456.00")); // 03-13 00:00 to 04-01 00:00
        assertThat(o.assignedTo().fullName()).isEqualTo("A-T1");
        assertThat(queries.overdue(scope, NOW, "high", 0, 20, false).totalElements()).isZero();
        assertThatThrownBy(() -> queries.overdue(scope, NOW, "x", 0, 20, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tenantsNeverMix() {
        TenantContext.set(b1.tenant());
        assertThat(service.volume(req(saB, null), "status").total()).isEqualTo(1);
        assertThat(queries.technicians(range, req(saB, null).scope(), 0, 20, false).content())
            .extracting(t -> t.technician().fullName()).containsExactly("B-T1");
        assertThat(queries.sla(range, req(saB, null).scope(), NOW).get(0).due()).isEqualTo(1);
        // tenant B admin cannot name a tenant A center
        assertThatThrownBy(() -> req(saB, a1.center())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void roleScopeIsEnforcedFromTheActor() {
        TenantContext.set(a1.tenant());
        assertThat(service.volume(req(hsA1, null), "status").total()).isEqualTo(9);       // own center only
        assertThat(service.volume(req(rsA, null), "status").total()).isEqualTo(10);        // whole region
        assertThatThrownBy(() -> req(hsA1, a2.center())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> req(hsA1, b1.center())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> req(tech, null)).isInstanceOf(AccessDeniedException.class);
        TenantContext.set(b1.tenant());
        assertThat(service.volume(req(hsB, null), "status").total()).isEqualTo(1);
        // a tenant B supervisor token cannot see tenant A even if the center id is guessed
        assertThatThrownBy(() -> req(hsB, a1.center())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void escalationsReportIsEmptyAndFlaggedWhenTheTableIsMissing() {
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('public.work_order_escalations') IS NOT NULL", Boolean.class));
        TenantContext.set(a1.tenant());
        EscalationReport e = service.escalations(req(saA, null));
        if (!exists) {
            assertThat(e.available()).isFalse();
            assertThat(e.groups()).isEmpty();
        } else {
            assertThat(e.available()).isTrue();
        }
    }

    @Test
    void rangeIsNotAffectedByTimestampInput() {
        // sanity: the Timestamp helper used in the fixtures round-trips in UTC
        Timestamp t = jdbc.queryForObject("SELECT '2026-03-05T08:00:00Z'::timestamptz", Timestamp.class);
        assertThat(t.toInstant()).isEqualTo(Instant.parse("2026-03-05T08:00:00Z"));
    }
}
