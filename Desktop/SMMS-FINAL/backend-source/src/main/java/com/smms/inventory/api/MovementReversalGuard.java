package com.smms.inventory.api;

import java.util.UUID;

/**
 * Optional extension point for modules that own documents linked to movements (material requests).
 * Implementations throw a WorkflowException (422) when the movement must not be reversed, for
 * example when its material request is closed. Called inside the reversal transaction.
 */
public interface MovementReversalGuard {
    void assertReversible(UUID movementId, UUID materialRequestLineId, UUID poReceiptLineId);
}
