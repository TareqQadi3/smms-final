package com.smms.security;

import java.util.Set;
import java.util.UUID;

/**
 * Immutable description of which work orders an actor may see. Produced by
 * {@link WorkOrderAccessPolicy#resolveScope} and turned into a JPA Specification by it.
 * A work order is visible when the scope is unrestricted, or ANY granted branch matches:
 * requested by the actor, assigned to the actor, or inside the location grant.
 */
public record WorkOrderScope(boolean unrestricted, UUID requestedById, UUID assignedToId, Location location) {

    /**
     * Location grant: every non-null component must match (AND). controlRoomIds == null means no
     * control-room constraint; an EMPTY set matches nothing. At least one of
     * centerId/regionId/controlRoomIds must be non-null, otherwise the grant would match every row.
     */
    public record Location(UUID centerId, UUID regionId, Set<UUID> controlRoomIds, boolean materialsOnly) {
        public Location {
            if (centerId == null && regionId == null && controlRoomIds == null) {
                throw new IllegalArgumentException("Location grant needs centerId, regionId or controlRoomIds");
            }
            controlRoomIds = controlRoomIds == null ? null : Set.copyOf(controlRoomIds);
        }
    }

    public static WorkOrderScope all() {
        return new WorkOrderScope(true, null, null, null);
    }

    public static WorkOrderScope none() {
        return new WorkOrderScope(false, null, null, null);
    }

    public static WorkOrderScope of(UUID requestedById, UUID assignedToId, Location location) {
        return new WorkOrderScope(false, requestedById, assignedToId, location);
    }

    public boolean isNone() {
        return !unrestricted && requestedById == null && assignedToId == null && location == null;
    }
}
