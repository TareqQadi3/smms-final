package com.smms.reports;

import com.smms.entity.User;
import com.smms.exception.ResourceNotFoundException;
import com.smms.security.WorkOrderScope;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.access.AccessDeniedException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Where a report may look: tenant plus an optional center or region. Derived from the SAME
 * {@link WorkOrderScope} the work-order list uses, but only location grants count: the personal
 * branches (own submissions, own assignments) are ignored, so a manager's report never mixes in
 * tickets they happened to submit in another center. Roles with no location grant (technician,
 * resident, department, other, warehouse, control room) get 403.
 *
 * @param tenantId null only for the platform administrator (every tenant)
 */
public record ReportScope(UUID tenantId, UUID centerId, UUID regionId) {

    /** Minimal center facts needed to validate a centerId filter. */
    public record CenterInfo(UUID tenantId, UUID regionId) {}

    /**
     * @param tenantContext the tenant of the request (TenantContext), null for the platform admin
     * @param centers lookup of a center id (empty when it does not exist)
     */
    public static ReportScope resolve(User actor, WorkOrderScope ws, UUID tenantContext,
                                      UUID centerFilter, UUID regionFilter,
                                      Function<UUID, Optional<CenterInfo>> centers) {
        if (actor == null || ws == null || ws.isNone()) throw denied();
        UUID tenant;
        UUID center = null;
        UUID region = null;
        boolean unrestricted = ws.unrestricted();
        if (unrestricted) {
            tenant = tenantContext;
        } else {
            WorkOrderScope.Location loc = ws.location();
            if (loc == null || loc.materialsOnly() || loc.controlRoomIds() != null) throw denied();
            tenant = actor.getTenantId();
            if (tenant == null) throw denied();
            center = loc.centerId();
            region = loc.regionId();
        }
        boolean regionGrant = !unrestricted && region != null && center == null;

        if (regionFilter != null) {
            if (!unrestricted && !regionGrant) throw denied(); // regionId is for system admin and region supervisor
            if (regionGrant && !regionFilter.equals(region)) throw notFound();
            region = regionFilter;
        }
        if (centerFilter != null) {
            CenterInfo info = centers.apply(centerFilter).orElseThrow(ReportScope::notFound);
            if (tenant != null && !tenant.equals(info.tenantId())) throw notFound();
            if (center != null && !center.equals(centerFilter)) throw notFound();
            if (region != null && !region.equals(info.regionId())) throw notFound();
            center = centerFilter;
        }
        return new ReportScope(tenant, center, region);
    }

    /**
     * SQL conditions (leading " AND ") for work_orders alias {@code w} joined to centers alias
     * {@code c}. Always excludes soft-deleted rows. Parameters are namespaced "sc_*".
     */
    public String sql(String w, String c, MapSqlParameterSource p) {
        StringBuilder sb = new StringBuilder(" AND ").append(w).append(".deleted_at IS NULL");
        if (tenantId != null) { sb.append(" AND ").append(w).append(".tenant_id = :sc_tenant"); p.addValue("sc_tenant", tenantId); }
        if (centerId != null) { sb.append(" AND ").append(w).append(".center_id = :sc_center"); p.addValue("sc_center", centerId); }
        if (regionId != null) { sb.append(" AND ").append(c).append(".region_id = :sc_region"); p.addValue("sc_region", regionId); }
        return sb.toString();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("You do not have access to reports");
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Center or region not found");
    }

    @Override public boolean equals(Object o) {
        return o instanceof ReportScope s && Objects.equals(tenantId, s.tenantId)
            && Objects.equals(centerId, s.centerId) && Objects.equals(regionId, s.regionId);
    }

    @Override public int hashCode() { return Objects.hash(tenantId, centerId, regionId); }
}
