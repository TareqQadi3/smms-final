package com.smms.util;

import com.smms.enums.PlanType;
import com.smms.enums.TenantStatus;

import java.util.UUID;

/**
 * Thread-local holder for the current tenant's context.
 * Set by JwtAuthFilter after JWT validation — NEVER from client.
 * Null tenantId means platform-level system_admin (no restrictions).
 */
public final class TenantContext {

    private TenantContext() {}

    private static final ThreadLocal<UUID>         TENANT_ID  = new ThreadLocal<>();
    private static final ThreadLocal<TenantStatus> STATUS     = new ThreadLocal<>();
    private static final ThreadLocal<PlanType>     PLAN       = new ThreadLocal<>();

    public static void set(UUID tenantId, TenantStatus status, PlanType plan) {
        TENANT_ID.set(tenantId);
        STATUS.set(status);
        PLAN.set(plan);
    }

    /** Legacy setter — used when tenant has no subscription yet (defaults to ACTIVE/FREE). */
    public static void set(UUID tenantId) {
        TENANT_ID.set(tenantId);
        STATUS.set(tenantId != null ? TenantStatus.ACTIVE : null);
        PLAN.set(tenantId != null ? PlanType.FREE : null);
    }

    public static UUID         get()       { return TENANT_ID.get(); }
    public static TenantStatus getStatus() { return STATUS.get(); }
    public static PlanType     getPlan()   { return PLAN.get() != null ? PLAN.get() : PlanType.FREE; }

    public static boolean isPlatformAdmin() { return TENANT_ID.get() == null; }

    public static boolean isSuspended() {
        TenantStatus s = STATUS.get();
        return s == TenantStatus.SUSPENDED;
    }

    public static boolean isExpired() {
        TenantStatus s = STATUS.get();
        return s == TenantStatus.EXPIRED;
    }

    /** Must be called in the filter's finally block to prevent thread-pool leaks. */
    public static void clear() {
        TENANT_ID.remove();
        STATUS.remove();
        PLAN.remove();
    }
}
