package com.smms.util;

import com.smms.enums.PlanType;
import com.smms.enums.TenantStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the thread-local TenantContext holder.
 * Critical: leaking a tenant context between threads is a data-isolation bug.
 */
class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void fullSetterRoundTrips() {
        UUID id = UUID.randomUUID();
        TenantContext.set(id, TenantStatus.ACTIVE, PlanType.PRO);

        assertThat(TenantContext.get()).isEqualTo(id);
        assertThat(TenantContext.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(TenantContext.getPlan()).isEqualTo(PlanType.PRO);
    }

    @Test
    @DisplayName("legacy setter defaults to ACTIVE/FREE for non-null tenant")
    void legacySetterDefaults() {
        UUID id = UUID.randomUUID();
        TenantContext.set(id);

        assertThat(TenantContext.get()).isEqualTo(id);
        assertThat(TenantContext.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(TenantContext.getPlan()).isEqualTo(PlanType.FREE);
    }

    @Test
    @DisplayName("null tenantId → platform admin mode")
    void nullTenantIdIsPlatformAdmin() {
        TenantContext.set(null, null, null);

        assertThat(TenantContext.get()).isNull();
        assertThat(TenantContext.isPlatformAdmin()).isTrue();
    }

    @Test
    void clearResetsAllValues() {
        TenantContext.set(UUID.randomUUID(), TenantStatus.ACTIVE, PlanType.ENTERPRISE);
        TenantContext.clear();

        assertThat(TenantContext.get()).isNull();
        assertThat(TenantContext.getStatus()).isNull();
        assertThat(TenantContext.isPlatformAdmin()).isTrue();
    }

    @Test
    void suspendedStatusIsDetected() {
        TenantContext.set(UUID.randomUUID(), TenantStatus.SUSPENDED, PlanType.FREE);
        assertThat(TenantContext.isSuspended()).isTrue();
        assertThat(TenantContext.isExpired()).isFalse();
    }

    @Test
    void expiredStatusIsDetected() {
        TenantContext.set(UUID.randomUUID(), TenantStatus.EXPIRED, PlanType.FREE);
        assertThat(TenantContext.isExpired()).isTrue();
        assertThat(TenantContext.isSuspended()).isFalse();
    }

    @Test
    @DisplayName("getPlan defaults to FREE when plan is null")
    void getPlanDefaultsToFree() {
        TenantContext.set(UUID.randomUUID(), TenantStatus.ACTIVE, null);
        assertThat(TenantContext.getPlan()).isEqualTo(PlanType.FREE);
    }

    @Test
    @DisplayName("context is isolated per thread")
    void contextIsThreadLocal() throws InterruptedException {
        UUID mainId = UUID.randomUUID();
        TenantContext.set(mainId, TenantStatus.ACTIVE, PlanType.PRO);

        UUID[] otherThreadId = {null};
        Thread other = new Thread(() -> {
            otherThreadId[0] = TenantContext.get(); // should be null — not set in this thread
            TenantContext.clear();
        });
        other.start();
        other.join();

        assertThat(otherThreadId[0]).isNull();
        assertThat(TenantContext.get()).isEqualTo(mainId); // main thread unaffected
    }
}
