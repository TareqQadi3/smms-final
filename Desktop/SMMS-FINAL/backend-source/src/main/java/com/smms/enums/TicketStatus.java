package com.smms.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TicketStatus {
    NEW("new"),
    UNDER_REVIEW("under_review"),
    ASSIGNED("assigned"),
    IN_PROGRESS("in_progress"),
    WAITING_MATERIALS("waiting_materials"),
    WAITING_APPROVAL("waiting_approval"),
    COMPLETED("completed"),
    CLOSED("closed"),
    CANCELLED("cancelled");

    private final String dbValue;

    TicketStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** JSON serialization is always the lowercase DB value (e.g. "under_review"). */
    @JsonValue
    public String getDbValue() {
        return dbValue;
    }

    public static TicketStatus fromDbValue(String value) {
        for (TicketStatus status : values()) {
            if (status.dbValue.equals(value)) return status;
        }
        throw new IllegalArgumentException("Invalid status: " + value);
    }

    /**
     * Lenient parser for JSON bodies and request params: accepts the lowercase DB value
     * ("under_review") or the enum name ("UNDER_REVIEW"), case-insensitively.
     * Returns null for null input so optional fields stay optional.
     */
    @JsonCreator
    public static TicketStatus fromString(String value) {
        if (value == null) return null;
        String v = value.trim();
        for (TicketStatus status : values()) {
            if (status.dbValue.equalsIgnoreCase(v) || status.name().equalsIgnoreCase(v)) return status;
        }
        throw new IllegalArgumentException("Invalid status: " + value);
    }

    @Override
    public String toString() {
        return dbValue;
    }
}
