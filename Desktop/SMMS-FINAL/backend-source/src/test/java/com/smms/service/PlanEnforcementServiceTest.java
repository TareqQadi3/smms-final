package com.smms.service;

import com.smms.enums.PlanType;
import com.smms.enums.TenantStatus;
import com.smms.exception.PlanLimitExceededException;
import com.smms.repository.CenterRepository;
import com.smms.repository.UserRepository;
import com.smms.repository.WorkOrderRepository;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for subscription plan limit enforcement.
 * FREE plan caps under test: 10 users, 100 tickets, 1 center.
 */
@ExtendWith(MockitoExtension.class)
class PlanEnforcementServiceTest {

    @Mock private UserRepository      userRepo;
    @Mock private WorkOrderRepository workOrderRepo;
    @Mock private CenterRepository    centerRepo;

    @InjectMocks
    private PlanEnforcementService service;

    private final UUID tenantId = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void asTenant(PlanType plan) {
        TenantContext.set(tenantId, TenantStatus.ACTIVE, plan);
    }

    @Nested
    @DisplayName("FREE plan limits")
    class FreePlan {

        @Test
        void allowsUserCreationBelowLimit() {
            asTenant(PlanType.FREE);
            when(userRepo.countByTenantIdAndDeletedAtIsNull(tenantId)).thenReturn(9L);
            assertThatCode(() -> service.checkUserLimit()).doesNotThrowAnyException();
        }

        @Test
        void blocksUserCreationAtLimit() {
            asTenant(PlanType.FREE);
            when(userRepo.countByTenantIdAndDeletedAtIsNull(tenantId)).thenReturn(10L);
            assertThatThrownBy(() -> service.checkUserLimit())
                .isInstanceOf(PlanLimitExceededException.class)
                .hasMessageContaining("User limit reached (10)");
        }

        @Test
        void blocksTicketCreationAtLimit() {
            asTenant(PlanType.FREE);
            when(workOrderRepo.countByTenantIdAndDeletedAtIsNull(tenantId)).thenReturn(100L);
            assertThatThrownBy(() -> service.checkTicketLimit())
                .isInstanceOf(PlanLimitExceededException.class)
                .hasMessageContaining("Ticket limit reached (100)");
        }

        @Test
        void blocksSecondCenterOnFreePlan() {
            asTenant(PlanType.FREE);
            when(centerRepo.countByTenantIdAndDeletedAtIsNull(tenantId)).thenReturn(1L);
            assertThatThrownBy(() -> service.checkCenterLimit())
                .isInstanceOf(PlanLimitExceededException.class)
                .hasMessageContaining("Center limit reached (1)");
        }
    }

    @Nested
    @DisplayName("PRO plan limits")
    class ProPlan {

        @Test
        void allowsBeyondFreeLimits() {
            asTenant(PlanType.PRO);
            when(userRepo.countByTenantIdAndDeletedAtIsNull(tenantId)).thenReturn(50L);
            assertThatCode(() -> service.checkUserLimit()).doesNotThrowAnyException();
        }

        @Test
        void blocksAtProUserCap() {
            asTenant(PlanType.PRO);
            when(userRepo.countByTenantIdAndDeletedAtIsNull(tenantId)).thenReturn(100L);
            assertThatThrownBy(() -> service.checkUserLimit())
                .isInstanceOf(PlanLimitExceededException.class);
        }
    }

    @Nested
    @DisplayName("ENTERPRISE plan and platform admin bypass")
    class Bypasses {

        @Test
        void enterprisePlanIsUnlimited_noRepositoryCalls() {
            asTenant(PlanType.ENTERPRISE);
            assertThatCode(() -> {
                service.checkUserLimit();
                service.checkTicketLimit();
                service.checkCenterLimit();
            }).doesNotThrowAnyException();
            verifyNoInteractions(userRepo, workOrderRepo, centerRepo);
        }

        @Test
        void platformAdminWithNullTenantBypassesAllChecks() {
            TenantContext.clear(); // null tenant = platform admin
            assertThatCode(() -> {
                service.checkUserLimit();
                service.checkTicketLimit();
                service.checkCenterLimit();
            }).doesNotThrowAnyException();
            verifyNoInteractions(userRepo, workOrderRepo, centerRepo);
        }
    }

    @Nested
    @DisplayName("Usage metrics")
    class Usage {

        @Test
        void returnsPerTenantCounts() {
            when(userRepo.countByTenantIdAndDeletedAtIsNull(tenantId)).thenReturn(3L);
            when(workOrderRepo.countByTenantIdAndDeletedAtIsNull(tenantId)).thenReturn(42L);
            when(centerRepo.countByTenantIdAndDeletedAtIsNull(tenantId)).thenReturn(1L);

            var usage = service.getUsage(tenantId);
            assertThat(usage.users()).isEqualTo(3);
            assertThat(usage.tickets()).isEqualTo(42);
            assertThat(usage.centers()).isEqualTo(1);
        }

        @Test
        void nullTenantReturnsGlobalCounts() {
            when(userRepo.count()).thenReturn(100L);
            when(workOrderRepo.count()).thenReturn(2000L);
            when(centerRepo.count()).thenReturn(15L);

            var usage = service.getUsage(null);
            assertThat(usage.users()).isEqualTo(100);
            assertThat(usage.tickets()).isEqualTo(2000);
            assertThat(usage.centers()).isEqualTo(15);
        }
    }
}
