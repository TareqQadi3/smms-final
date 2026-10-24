package com.smms.inventory.service;

import com.smms.entity.BaseEntity;
import com.smms.entity.Center;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.util.TenantContext;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

import static com.smms.enums.UserRole.*;

/**
 * Role, tenant and center/region scoping of the inventory module (permission matrix, plan 2.11).
 * Out-of-scope objects are "not found" (404); a visible object the role may not act on is 403.
 * The actor is the detached JWT principal: only proxy ids of center/region are read.
 */
@Component
public class InventoryAccess {

    public static final Set<UserRole> STOCK_READERS = Set.of(system_admin, region_supervisor,
        hospital_supervisor, maintenance_manager, electrical_manager, operating_company_manager, warehouse);
    public static final Set<UserRole> CATALOG_READERS = Set.of(system_admin, region_supervisor,
        hospital_supervisor, maintenance_manager, electrical_manager, operating_company_manager, warehouse,
        control_room, technician);
    public static final Set<UserRole> CATALOG_WRITERS = Set.of(system_admin, maintenance_manager, warehouse);
    public static final Set<UserRole> WAREHOUSE_WRITERS = Set.of(system_admin, maintenance_manager, hospital_supervisor);
    public static final Set<UserRole> UOM_WRITERS = Set.of(system_admin, maintenance_manager);
    public static final Set<UserRole> OPERATORS = Set.of(system_admin, maintenance_manager, warehouse);
    public static final Set<UserRole> CORRECTORS = Set.of(system_admin, maintenance_manager);

    public enum Kind { ALL, REGION, CENTER, NONE }

    public record Scope(Kind kind, UUID regionId, UUID centerId) {}

    public Scope scope(User actor) {
        if (actor == null || actor.getRole() == null) return new Scope(Kind.NONE, null, null);
        UUID c = actor.getCenter() != null ? actor.getCenter().getId() : null;
        UUID r = actor.getRegion() != null ? actor.getRegion().getId() : null;
        return switch (actor.getRole()) {
            case system_admin -> new Scope(Kind.ALL, null, null);
            case region_supervisor -> r == null ? new Scope(Kind.NONE, null, null) : new Scope(Kind.REGION, r, null);
            case hospital_supervisor, maintenance_manager, electrical_manager, operating_company_manager,
                 warehouse, control_room, technician ->
                c == null ? new Scope(Kind.NONE, null, null) : new Scope(Kind.CENTER, null, c);
            default -> new Scope(Kind.NONE, null, null);
        };
    }

    public boolean has(User actor, Set<UserRole> roles) {
        return actor != null && actor.getRole() != null && roles.contains(actor.getRole());
    }

    /** 403 unless the actor's role is in the set. */
    public void requireRole(User actor, Set<UserRole> roles) {
        if (!has(actor, roles)) throw new AccessDeniedException("Role not permitted");
    }

    public boolean centerInScope(User actor, Center center) {
        if (center == null) return false;
        Scope s = scope(actor);
        return switch (s.kind()) {
            case ALL -> true;
            case REGION -> center.getRegion() != null && s.regionId().equals(center.getRegion().getId());
            case CENTER -> s.centerId().equals(center.getId());
            case NONE -> false;
        };
    }

    /** Tenant isolation for a loaded row: a tenant context must equal the row's tenant. */
    public boolean tenantVisible(BaseEntity row) {
        UUID ctx = TenantContext.get();
        return ctx == null || (row != null && ctx.equals(row.getTenantId()));
    }

    public void requireCenterInScope(User actor, Center center) {
        if (!centerInScope(actor, center) || !tenantVisible(center)) throw new ResourceNotFoundException("Center not found");
    }

    /**
     * Restricts a query on an entity reached through {@code centerPath} (a join on Center) to the actor's
     * scope plus the tenant of the context. {@code none} yields a false predicate.
     */
    public <T> Predicate scopePredicate(User actor, jakarta.persistence.criteria.CriteriaBuilder cb,
                                        From<?, ?> center, jakarta.persistence.criteria.Path<UUID> tenantCol) {
        Scope s = scope(actor);
        UUID tenant = TenantContext.get();
        Predicate p = switch (s.kind()) {
            case ALL -> cb.conjunction();
            case REGION -> cb.equal(center.get("region").get("id"), s.regionId());
            case CENTER -> cb.equal(center.get("id"), s.centerId());
            case NONE -> cb.disjunction();
        };
        if (tenant != null) p = cb.and(p, cb.equal(tenantCol, tenant));
        return p;
    }

    public <T extends BaseEntity> Specification<T> notDeleted() {
        return (root, q, cb) -> cb.isNull(root.get("deletedAt"));
    }
}
