package com.smms.materials.entity;

import java.util.EnumSet;
import java.util.Set;

/** Material-request state machine (issuing more lines while {@code issued} is not a transition). */
public enum MaterialRequestStatus {
    submitted, approved, rejected, reserved, issued, closed, cancelled;

    public Set<MaterialRequestStatus> next() {
        return switch (this) {
            case submitted -> EnumSet.of(approved, rejected, cancelled);
            case approved -> EnumSet.of(reserved, issued, cancelled);
            case reserved -> EnumSet.of(issued, cancelled);
            case issued -> EnumSet.of(closed);
            case rejected, closed, cancelled -> EnumSet.noneOf(MaterialRequestStatus.class);
        };
    }

    public boolean canMoveTo(MaterialRequestStatus t) { return next().contains(t); }

    public boolean isTerminal() { return next().isEmpty(); }

    /** Open documents shown in the open-requests report. */
    public boolean isOpen() { return this == submitted || this == approved || this == reserved || this == issued; }
}
