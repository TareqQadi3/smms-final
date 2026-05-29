package com.smms.enums;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum TicketStatus {
    @JsonProperty("new")
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

    public String getDbValue() {
        return dbValue;
    }

    public static TicketStatus fromDbValue(String value) {
        for (TicketStatus status : values()) {
            if (status.dbValue.equals(value)) return status;
        }
        throw new IllegalArgumentException("Invalid status: " + value);
    }

    @Override
    public String toString() {
        return dbValue;
    }
}
