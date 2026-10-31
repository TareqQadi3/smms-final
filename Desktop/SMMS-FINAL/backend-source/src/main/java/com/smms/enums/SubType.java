package com.smms.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum SubType {
    electrical, plumbing, hvac, civil,
    printer, network, software, devices,
    equipment_failure, preventive_maintenance, calibration;

    /**
     * Lenient JSON parser: accepts the enum name case-insensitively ("HVAC" -> hvac).
     * Arbitrary display labels (e.g. "Civil Works") are intentionally NOT mapped; they are rejected.
     */
    @JsonCreator
    public static SubType fromString(String value) {
        if (value == null) return null;
        String v = value.trim();
        for (SubType t : values()) {
            if (t.name().equalsIgnoreCase(v)) return t;
        }
        throw new IllegalArgumentException("Invalid subType: " + value);
    }
}
