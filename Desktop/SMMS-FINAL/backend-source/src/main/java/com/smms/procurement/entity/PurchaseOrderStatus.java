package com.smms.procurement.entity;

import java.util.EnumSet;
import java.util.Set;

/** Purchase-order state machine: draft, submitted, then approved/rejected, receiving, close/cancel. */
public enum PurchaseOrderStatus {
    draft, submitted, approved, rejected, partially_received, received, cancelled, closed;

    /** States reachable in one step (receiving into the same state is not a transition). */
    public Set<PurchaseOrderStatus> next() {
        return switch (this) {
            case draft -> EnumSet.of(submitted, cancelled);
            case submitted -> EnumSet.of(approved, rejected, cancelled);
            case approved -> EnumSet.of(partially_received, received, cancelled, closed);
            case partially_received -> EnumSet.of(received, closed);
            case rejected, received, cancelled, closed -> EnumSet.noneOf(PurchaseOrderStatus.class);
        };
    }

    public boolean canMoveTo(PurchaseOrderStatus target) { return next().contains(target); }

    public boolean isTerminal() { return next().isEmpty(); }

    /** Receiving is possible in these states. */
    public boolean canReceive() { return this == approved || this == partially_received; }
}
