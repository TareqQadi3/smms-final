package com.smms.assets;

import com.smms.entity.Asset;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.util.TenantContext;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who may read and write the asset register (permission matrix of FP1). Tenant is always applied
 * when a tenant context exists. Out-of-scope objects are 404, a forbidden action on a visible
 * object is 403.
 * Read: system_admin (tenant), region_supervisor (region), hospital/maintenance/electrical/operating
 * company managers, control room, warehouse and technician (own center), department (assets of the
 * user's department). Everything else sees nothing.
 * Write: system_admin, hospital_supervisor, maintenance_manager, electrical_manager (own center).
 */
@Component
public class AssetAccessPolicy {

    public static final Set<UserRole> WRITERS = Set.of(UserRole.system_admin, UserRole.hospital_supervisor,
        UserRole.maintenance_manager, UserRole.electrical_manager);

    public record Scope(boolean all, UUID regionId, UUID centerId, UUID departmentId) {
        public boolean none() { return !all && regionId == null && centerId == null && departmentId == null; }
    }

    public Scope readScope(User actor) {
        if (actor == null || actor.getRole() == null) return new Scope(false, null, null, null);
        UUID c = actor.getCenter() != null ? actor.getCenter().getId() : null;
        UUID r = actor.getRegion() != null ? actor.getRegion().getId() : null;
        UUID d = actor.getDepartment() != null ? actor.getDepartment().getId() : null;
        return switch (actor.getRole()) {
            case system_admin -> new Scope(true, null, null, null);
            case region_supervisor -> new Scope(false, r, null, null);
            case hospital_supervisor, maintenance_manager, electrical_manager, operating_company_manager,
                 control_room, warehouse, technician -> new Scope(false, null, c, null);
            case department -> new Scope(false, null, null, d);
            default -> new Scope(false, null, null, null);
        };
    }

    public Specification<Asset> readable(User actor) {
        Scope scope = readScope(actor);
        UUID tenantId = TenantContext.get();
        return (root, q, cb) -> {
            List<Predicate> and = new ArrayList<>();
            and.add(cb.isNull(root.get("deletedAt")));
            if (tenantId != null) and.add(cb.equal(root.get("tenantId"), tenantId));
            if (scope.none()) return cb.disjunction();
            if (!scope.all()) {
                if (scope.regionId() != null) and.add(cb.equal(root.get("center").get("region").get("id"), scope.regionId()));
                if (scope.centerId() != null) and.add(cb.equal(root.get("center").get("id"), scope.centerId()));
                if (scope.departmentId() != null) and.add(cb.equal(root.get("department").get("id"), scope.departmentId()));
            }
            return cb.and(and.toArray(new Predicate[0]));
        };
    }

    public boolean canWrite(User actor) {
        return actor != null && actor.getRole() != null && WRITERS.contains(actor.getRole());
    }

    /** Role gate (403). */
    public void assertWriter(User actor) {
        if (!canWrite(actor)) throw new AccessDeniedException("Your role may not change assets");
    }

    /** Center-level write scope: system_admin any center of the tenant, others only their own. */
    public boolean centerWritable(User actor, UUID centerId) {
        if (!canWrite(actor) || centerId == null) return false;
        if (actor.getRole() == UserRole.system_admin) return true;
        return actor.getCenter() != null && centerId.equals(actor.getCenter().getId());
    }

    /** Write on an existing asset: invisible = 404, visible but not writable = 403. */
    public void assertWritable(User actor, Asset asset) {
        assertWriter(actor);
        UUID center = asset.getCenter() != null ? asset.getCenter().getId() : null;
        if (!centerWritable(actor, center)) throw new ResourceNotFoundException("Asset not found");
    }
}
