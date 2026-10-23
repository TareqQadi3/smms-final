package com.smms.security;

import com.smms.entity.BaseEntity;
import com.smms.entity.Center;
import com.smms.entity.ControlRoom;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.TenantRepository;
import com.smms.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Single place for the rules that decide who may create or change users, centers, control rooms
 * and regions, and which tenant a new row belongs to (SEC-A/C/D). Mirrors WorkOrderAccessPolicy.
 *
 * Error contract:
 *  - a referenced center/region/control room/user outside the actor's scope is reported as
 *    "not found" (404), so ids are not an existence oracle;
 *  - a forbidden action on something the actor can see (role rank, role assignment) is 403;
 *  - inconsistent input (mixed tenants, missing tenantId) is 400.
 *
 * The actor is the detached JWT principal (open-in-view is off): only proxy ids are read from
 * its center/region, never center.region.
 */
@Component
@RequiredArgsConstructor
public class AdminScopePolicy {

    private final TenantRepository tenantRepo;

    public enum Kind { ALL, REGION, CENTER, NONE }

    /** Which users an actor may list. regionId/centerId are set for REGION/CENTER only. */
    public record UserListScope(Kind kind, UUID regionId, UUID centerId, Set<UserRole> excludedRoles) {
        static UserListScope none() { return new UserListScope(Kind.NONE, null, null, Set.of()); }
    }

    private static final Set<UserRole> LIST_HIDDEN = Set.of(UserRole.system_admin, UserRole.region_supervisor);

    // ── Ranks and role assignment ────────────────────────────

    public int rank(UserRole role) {
        if (role == null) return 10;
        return switch (role) {
            case system_admin -> 100;
            case region_supervisor -> 80;
            case hospital_supervisor -> 60;
            default -> 10;
        };
    }

    /** May the actor create a user with, or promote a user to, this role? (403 otherwise) */
    public void assertCanAssignRole(User actor, UserRole target) {
        if (actor == null) return; // system bootstrap, never reachable from a request
        if (actor.getRole() == null) throw new AccessDeniedException("Your role is not authorized to create users");
        switch (actor.getRole()) {
            case system_admin -> {
                // Only the platform-level admin (no tenant context) may mint another platform admin
                if (target == UserRole.system_admin && !TenantContext.isPlatformAdmin()) {
                    throw new AccessDeniedException("Only the platform administrator can create system_admin accounts");
                }
            }
            case region_supervisor -> {
                if (rank(target) >= rank(actor.getRole())) {
                    throw new AccessDeniedException(
                        "Region supervisors cannot create system_admin or region_supervisor accounts");
                }
            }
            case hospital_supervisor -> {
                if (rank(target) >= rank(actor.getRole())) {
                    throw new AccessDeniedException(
                        "Hospital supervisors cannot create admin/supervisor-level accounts");
                }
            }
            default -> throw new AccessDeniedException("Your role is not authorized to create users");
        }
    }

    // ── Scope of referenced objects (404 when outside) ───────

    public void assertCenterInScope(User actor, Center center) {
        if (!centerInScope(actor, center)) throw new ResourceNotFoundException("Center not found");
    }

    public void assertRegionInScope(User actor, Region region) {
        if (!regionInScope(actor, region)) throw new ResourceNotFoundException("Region not found");
    }

    public void assertControlRoomInScope(User actor, ControlRoom cr) {
        if (cr == null || !centerInScope(actor, cr.getCenter())) {
            throw new ResourceNotFoundException("ControlRoom not found");
        }
    }

    public boolean centerInScope(User actor, Center center) {
        if (actor == null || actor.getRole() == null || center == null) return false;
        return switch (actor.getRole()) {
            case system_admin -> true;
            case region_supervisor -> {
                UUID own = regionId(actor);
                yield own != null && center.getRegion() != null && own.equals(center.getRegion().getId());
            }
            case hospital_supervisor -> {
                UUID own = centerId(actor);
                yield own != null && own.equals(center.getId());
            }
            default -> false;
        };
    }

    public boolean regionInScope(User actor, Region region) {
        if (actor == null || actor.getRole() == null || region == null) return false;
        return switch (actor.getRole()) {
            case system_admin -> true;
            case region_supervisor -> {
                UUID own = regionId(actor);
                yield own != null && own.equals(region.getId());
            }
            // A hospital supervisor never sets a region; the user's region is derived from the center
            default -> false;
        };
    }

    // ── Managing an existing user ────────────────────────────

    /**
     * May the actor modify this user (update, activate/deactivate, grant access)?
     * Scope is checked first (404, so users of other centers/regions are invisible), then rank (403).
     */
    public void assertCanManageUser(User actor, User target) {
        if (actor == null || actor.getRole() == null || target == null) {
            throw new AccessDeniedException("Insufficient privilege to manage this account");
        }
        if (actor.getRole() == UserRole.system_admin) return;
        boolean inScope = switch (actor.getRole()) {
            case region_supervisor -> {
                UUID own = regionId(actor);
                UUID targetRegion = target.getRegion() != null ? target.getRegion().getId()
                    : (target.getCenter() != null && target.getCenter().getRegion() != null
                        ? target.getCenter().getRegion().getId() : null);
                yield own != null && own.equals(targetRegion);
            }
            case hospital_supervisor -> {
                UUID own = centerId(actor);
                yield own != null && target.getCenter() != null && own.equals(target.getCenter().getId());
            }
            default -> false;
        };
        if (!inScope) throw new ResourceNotFoundException("User not found");
        if (rank(target.getRole()) >= rank(actor.getRole())) {
            throw new AccessDeniedException("Insufficient privilege to manage this account");
        }
    }

    // ── User list scope ──────────────────────────────────────

    public UserListScope resolveUserListScope(User actor) {
        if (actor == null || actor.getRole() == null) return UserListScope.none();
        return switch (actor.getRole()) {
            case system_admin -> new UserListScope(Kind.ALL, null, null, Set.of());
            case region_supervisor -> regionId(actor) == null ? UserListScope.none()
                : new UserListScope(Kind.REGION, regionId(actor), null, LIST_HIDDEN);
            case hospital_supervisor -> centerId(actor) == null ? UserListScope.none()
                : new UserListScope(Kind.CENTER, null, centerId(actor), LIST_HIDDEN);
            default -> UserListScope.none();
        };
    }

    // ── Tenant of a new row ──────────────────────────────────

    /**
     * Tenant for a new tenant-owned row, derived server-side from the request's tenant context or
     * from the parents it is attached to; never taken blindly from the client and never null.
     *  - tenant admins (context tenant set): always the context tenant, requestedTenantId is ignored;
     *  - platform admin: the single tenant of the anchors; requestedTenantId may only confirm it,
     *    or must name an existing tenant when there are no anchors.
     */
    public UUID resolveTenantForNewEntity(UUID requestedTenantId, BaseEntity... anchors) {
        UUID ctx = TenantContext.get();
        if (ctx != null) {
            for (BaseEntity a : anchors) {
                if (a != null && !ctx.equals(a.getTenantId())) {
                    throw new ResourceNotFoundException("Referenced record not found");
                }
            }
            return ctx;
        }
        Set<UUID> tenants = new LinkedHashSet<>();
        for (BaseEntity a : anchors) {
            if (a == null) continue;
            if (a.getTenantId() == null) {
                throw new IllegalArgumentException("Referenced record has no tenant assigned");
            }
            tenants.add(a.getTenantId());
        }
        if (tenants.size() > 1) throw new IllegalArgumentException("Inconsistent tenant");
        if (tenants.size() == 1) {
            UUID t = tenants.iterator().next();
            if (requestedTenantId != null && !requestedTenantId.equals(t)) {
                throw new IllegalArgumentException("tenantId does not match the referenced records");
            }
            return t;
        }
        if (requestedTenantId == null) throw new IllegalArgumentException("tenantId is required");
        if (!tenantRepo.existsById(requestedTenantId)) throw new IllegalArgumentException("Unknown tenantId");
        return requestedTenantId;
    }

    /** All non-null parts must share one tenant, else 400. */
    public void assertSameTenant(BaseEntity... parts) {
        UUID first = null;
        boolean any = false;
        for (BaseEntity p : parts) {
            if (p == null) continue;
            if (!any) { first = p.getTenantId(); any = true; }
            else if (!Objects.equals(first, p.getTenantId())) {
                throw new IllegalArgumentException("Inconsistent tenant");
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────

    private static UUID centerId(User u) { return u.getCenter() != null ? u.getCenter().getId() : null; }
    private static UUID regionId(User u) { return u.getRegion() != null ? u.getRegion().getId() : null; }
}
