package com.smms.exception;

/** Thrown when a tenant is SUSPENDED or EXPIRED. Maps to HTTP 403 / 402. */
public class TenantSuspendedException extends RuntimeException {
    private final boolean expired;
    public TenantSuspendedException(String message, boolean expired) {
        super(message);
        this.expired = expired;
    }
    public boolean isExpired() { return expired; }
}
