package com.smms.inventory.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Optional: resolves the material request / purchase order a movement belongs to (owned by procurement/materials). */
public interface MovementLinkProvider {
    record Links(UUID materialRequestId, UUID poId) {}

    /** Keyed by movement id; missing keys mean no links. */
    Map<UUID, Links> resolve(Collection<UUID> movementIds);
}
