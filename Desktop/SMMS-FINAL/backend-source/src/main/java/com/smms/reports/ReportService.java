package com.smms.reports;

import com.smms.entity.User;
import com.smms.reports.ReportModels.*;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.util.PageRequests;
import com.smms.util.TenantContext;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operational reports. Order of checks on every call: range (400), scope from the actor's role (403),
 * center/region filters (403/404), then the query. The controller restricts the roles; this class
 * re-derives the scope from the actor so a report can never be wider than the work-order list.
 */
@Service
public class ReportService {

    private final ReportQueries queries;
    private final WorkOrderAccessPolicy policy;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ReportService(ReportQueries queries, WorkOrderAccessPolicy policy) {
        this(queries, policy, Clock.systemUTC());
    }

    public ReportService(ReportQueries queries, WorkOrderAccessPolicy policy, Clock clock) {
        this.queries = queries;
        this.policy = policy;
        this.clock = clock;
    }

    public record Request(ReportRange range, ReportScope scope) {}

    public Request resolve(User actor, LocalDate from, LocalDate to, UUID centerId, UUID regionId) {
        ReportRange range = ReportRange.parse(from, to, clock);
        return new Request(range, scope(actor, centerId, regionId));
    }

    public ReportScope scope(User actor, UUID centerId, UUID regionId) {
        return ReportScope.resolve(actor, policy.resolveScope(actor), TenantContext.get(), centerId, regionId, queries::center);
    }

    public Volume volume(Request r, String groupBy) {
        String g = groupBy == null ? "status" : groupBy;
        if (!ReportQueries.VOLUME_GROUPS.contains(g)) throw new IllegalArgumentException("Unsupported groupBy");
        List<Group> groups = queries.volume(r.range(), r.scope(), g);
        return new Volume(r.range().from(), r.range().to(), g, groups, groups.stream().mapToLong(Group::count).sum());
    }

    public TurnaroundReport turnaround(Request r, String groupBy) {
        String g = groupBy == null ? "priority" : groupBy;
        if (!ReportQueries.TURNAROUND_GROUPS.contains(g)) throw new IllegalArgumentException("Unsupported groupBy");
        return new TurnaroundReport(r.range().from(), r.range().to(), g, queries.turnaround(r.range(), r.scope(), g));
    }

    public SlaReport sla(Request r) {
        return new SlaReport(r.range().from(), r.range().to(), queries.sla(r.range(), r.scope(), clock.instant()));
    }

    public EscalationReport escalations(Request r) {
        return queries.escalations(r.range(), r.scope())
            .orElseGet(() -> new EscalationReport(r.range().from(), r.range().to(), false, List.of(), List.of()));
    }

    public Page<TechnicianRow> technicians(Request r, int page, int size, boolean all) {
        Pageable pg = PageRequests.of(page, size, null, Map.of(), Sort.by("fullName"));
        return queries.technicians(r.range(), r.scope(), pg.getPageNumber(), pg.getPageSize(), all);
    }

    /** Snapshot as of now: open tickets past their SLA deadline whose clock is not paused. */
    public Page<OverdueRow> overdue(User actor, UUID centerId, UUID regionId, String priority, int page, int size, boolean all) {
        ReportScope scope = scope(actor, centerId, regionId);
        Pageable pg = PageRequests.of(page, size, null, Map.of(), Sort.by("slaDeadline"));
        return queries.overdue(scope, clock.instant(), priority, pg.getPageNumber(), pg.getPageSize(), all);
    }
}
