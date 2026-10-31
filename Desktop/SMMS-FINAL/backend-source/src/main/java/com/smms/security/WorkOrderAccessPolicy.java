package com.smms.security;

import com.smms.entity.ControlRoom;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.UserControlRoomAccessRepository;
import com.smms.repository.WorkOrderRepository;
import com.smms.security.WorkOrderScope.Location;
import com.smms.util.TenantContext;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Single source of truth for work-order visibility (DEF-005). Every list, single-object read and
 * mutating path goes through this class so list and detail can never disagree.
 *
 * Role to scope (anything not listed, a null actor or a null role is denied):
 *  - system_admin: everything (tenant predicate still applied when a tenant context exists)
 *  - technician: only tickets assigned to them
 *  - resident, department: only tickets they submitted
 *  - control_room: own submissions, plus tickets in their center AND in their assigned control rooms
 *  - hospital_supervisor, maintenance_manager, electrical_manager, operating_company_manager:
 *    own submissions, plus every ticket of their center
 *  - warehouse: tickets of their center that need materials
 *  - region_supervisor: tickets of centers in their region
 *  - other: nothing
 * Missing scope data (no center, no region, no control-room rows) never widens access: the
 * corresponding grant is simply not given.
 * Out-of-scope single-object access is reported as "not found" so ticket ids are not an oracle.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkOrderAccessPolicy {

    private final WorkOrderRepository workOrderRepo;
    private final UserControlRoomAccessRepository accessRepo;

    /** Maps the actor's role and assignments to a scope. One id-only query at most (control_room). */
    public WorkOrderScope resolveScope(User actor) {
        if (actor == null || actor.getId() == null || actor.getRole() == null) {
            return WorkOrderScope.none();
        }
        UUID self = actor.getId();
        // The principal is detached (open-in-view is off): read proxy ids only, never navigate
        // center -> region.
        UUID c = actor.getCenter() != null ? actor.getCenter().getId() : null;
        UUID r = actor.getRegion() != null ? actor.getRegion().getId() : null;
        return switch (actor.getRole()) {
            case system_admin -> WorkOrderScope.all();
            case technician -> WorkOrderScope.of(null, self, null);
            case resident, department -> WorkOrderScope.of(self, null, null);
            case control_room -> {
                // Without a center there is no location grant, so skip the access-row query
                if (c == null) yield WorkOrderScope.of(self, null, null);
                Set<UUID> rooms = Set.copyOf(accessRepo.findControlRoomIdsByUserId(self));
                // Center AND assigned rooms: a cross-center room grant does not expose that center
                yield WorkOrderScope.of(self, null,
                    rooms.isEmpty() ? null : new Location(c, null, rooms, false));
            }
            case hospital_supervisor, maintenance_manager, electrical_manager, operating_company_manager ->
                WorkOrderScope.of(self, null, c == null ? null : new Location(c, null, null, false));
            // needs_materials is set on every path into waiting_materials and never cleared, so it
            // keeps the tickets the warehouse handles and hides the rest
            case warehouse -> c == null ? WorkOrderScope.none()
                : WorkOrderScope.of(null, null, new Location(c, null, null, true));
            case region_supervisor -> r == null ? WorkOrderScope.none()
                : WorkOrderScope.of(null, null, new Location(null, r, null, false));
            case other -> WorkOrderScope.none();
        };
    }

    /** Predicate for the scope plus soft-delete and tenant isolation (platform admin has no tenant). */
    public Specification<WorkOrder> specFor(WorkOrderScope scope) {
        UUID tenantId = TenantContext.get();
        return (root, query, cb) -> {
            List<Predicate> and = new ArrayList<>();
            and.add(cb.isNull(root.get("deletedAt")));
            if (tenantId != null) and.add(cb.equal(root.get("tenantId"), tenantId));
            if (!scope.unrestricted()) {
                // Granted branches are ORed; an empty scope is an always-false predicate
                List<Predicate> or = new ArrayList<>();
                if (scope.requestedById() != null) {
                    or.add(cb.equal(root.get("requestedBy").get("id"), scope.requestedById()));
                }
                if (scope.assignedToId() != null) {
                    or.add(cb.equal(root.get("assignedTo").get("id"), scope.assignedToId()));
                }
                Location loc = scope.location();
                if (loc != null) {
                    // Every component of the location grant must match
                    List<Predicate> l = new ArrayList<>();
                    if (loc.centerId() != null) l.add(cb.equal(root.get("center").get("id"), loc.centerId()));
                    if (loc.regionId() != null) {
                        l.add(cb.equal(root.get("center").get("region").get("id"), loc.regionId()));
                    }
                    if (loc.controlRoomIds() != null) {
                        // An empty set must match nothing (and never render IN ())
                        l.add(loc.controlRoomIds().isEmpty()
                            ? cb.disjunction()
                            : root.get("controlRoom").get("id").in(loc.controlRoomIds()));
                    }
                    if (loc.materialsOnly()) l.add(cb.isTrue(root.get("needsMaterials")));
                    or.add(cb.and(l.toArray(new Predicate[0])));
                }
                and.add(or.isEmpty() ? cb.disjunction() : cb.or(or.toArray(new Predicate[0])));
            }
            return cb.and(and.toArray(new Predicate[0]));
        };
    }

    public Specification<WorkOrder> readableBy(User actor) {
        return specFor(resolveScope(actor));
    }

    /**
     * Loads the work order only when the actor may see it; otherwise 404, identical to a missing or
     * cross-tenant id. Used by every single-object read and write path.
     */
    public WorkOrder requireReadable(UUID id, User actor) {
        WorkOrderScope scope = resolveScope(actor);
        if (id != null && !scope.isNone()) {
            Specification<WorkOrder> byId = (root, q, cb) -> cb.equal(root.get("id"), id);
            Optional<WorkOrder> wo = workOrderRepo.findOne(byId.and(specFor(scope)));
            if (wo.isPresent()) {
                if (scope.unrestricted() && TenantContext.get() == null) {
                    log.info("AUDIT platform-admin work-order access: workOrder={} actor={}", id, actor.getId());
                }
                return wo.get();
            }
        }
        throw new ResourceNotFoundException("WorkOrder not found: " + id);
    }

    /** Create-side check: may the actor submit a ticket to this control room? (403 on failure) */
    public void assertCanCreateIn(ControlRoom cr, User actor) {
        if (actor == null || actor.getRole() == null) throw denied();
        UUID crCenter = cr.getCenter() != null ? cr.getCenter().getId() : null;
        UUID actorCenter = actor.getCenter() != null ? actor.getCenter().getId() : null;
        boolean sameCenter = crCenter != null && crCenter.equals(actorCenter);
        boolean allowed = switch (actor.getRole()) {
            case system_admin -> true;
            // Supervisors and maintenance managers may submit only inside their own center
            case hospital_supervisor, maintenance_manager -> sameCenter;
            // Control room operators need an explicit room grant AND the same center
            case control_room -> sameCenter && hasRoomAccess(actor, cr);
            // End users need an explicit room grant
            case resident, department -> hasRoomAccess(actor, cr);
            default -> false;
        };
        if (!allowed) throw denied();
    }

    private boolean hasRoomAccess(User actor, ControlRoom cr) {
        return accessRepo.existsByUserIdAndControlRoomId(actor.getId(), cr.getId());
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("You do not have access to this control room");
    }
}
