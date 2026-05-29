package com.smms.enums;

public enum TenantStatus {
    ACTIVE,      // full access
    SUSPENDED,   // read-only — 403 on write operations
    EXPIRED      // plan expired — login blocked except platform admin
}
