package com.smms.procurement;

import com.smms.entity.Center;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.util.TenantContext;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/**
 * Location scope of an actor for procurement and inventory documents (permission matrix, plan 2.11):
 * system_admin = whole tenant (ALL), region_supervisor = centers of the region, every other
 * operational role = own center. Missing center/region data never widens access (NONE).
 * The tenant is always applied on top, from the request's {@link TenantContext}.
 */
public record CenterScope(Kind kind, UUID regionId, UUID centerId) {

    public enum Kind { ALL, REGION, CENTER, NONE }

    public static CenterScope of(User actor) {
        if (actor == null || actor.getRole() == null) return new CenterScope(Kind.NONE, null, null);
        UUID c = actor.getCenter() != null ? actor.getCenter().getId() : null;
        UUID r = actor.getRegion() != null ? actor.getRegion().getId() : null;
        return switch (actor.getRole()) {
            case system_admin -> new CenterScope(Kind.ALL, null, null);
            case region_supervisor -> r == null ? none() : new CenterScope(Kind.REGION, r, null);
            case resident, department, other -> none();
            default -> c == null ? none() : new CenterScope(Kind.CENTER, null, c);
        };
    }

    private static CenterScope none() { return new CenterScope(Kind.NONE, null, null); }

    /** Is a center (with its region id) inside the scope? */
    public boolean contains(UUID center, UUID region) {
        return switch (kind) {
            case ALL -> true;
            case REGION -> regionId != null && regionId.equals(region);
            case CENTER -> centerId != null && centerId.equals(center);
            case NONE -> false;
        };
    }

    public boolean contains(Center center) {
        if (center == null) return false;
        return contains(center.getId(), center.getRegion() != null ? center.getRegion().getId() : null);
    }

    /** 404 when the center is outside the scope (no existence oracle). */
    public void requireContains(Center center, String what) {
        if (!contains(center)) throw new ResourceNotFoundException(what + " not found");
    }

    /** Tenant of the request must match the row (platform admin has no tenant context). */
    public static boolean tenantMatches(UUID rowTenant) {
        UUID ctx = TenantContext.get();
        return ctx == null || ctx.equals(rowTenant);
    }

    /** Spec on an entity with a {@code center} association and a {@code tenantId}. */
    public <T> Specification<T> spec() {
        UUID tenant = TenantContext.get();
        return (root, q, cb) -> {
            Predicate p = tenant == null ? cb.conjunction() : cb.equal(root.get("tenantId"), tenant);
            return switch (kind) {
                case ALL -> p;
                case REGION -> cb.and(p, cb.equal(root.get("center").get("region").get("id"), regionId));
                case CENTER -> cb.and(p, cb.equal(root.get("center").get("id"), centerId));
                case NONE -> cb.disjunction();
            };
        };
    }

    /** 403 unless the actor has one of the roles. */
    public static void requireRole(User actor, UserRole... roles) {
        if (actor == null || actor.getRole() == null || !Arrays.asList(roles).contains(actor.getRole())) {
            throw new AccessDeniedException("Your role is not allowed to perform this action");
        }
    }

    public static boolean hasRole(User actor, Set<UserRole> roles) {
        return actor != null && actor.getRole() != null && roles.contains(actor.getRole());
    }
}
