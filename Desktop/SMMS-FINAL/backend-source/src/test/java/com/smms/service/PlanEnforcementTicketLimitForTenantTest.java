package com.smms.service;

import com.smms.cache.SubscriptionCache;
import com.smms.config.PlanConfig;
import com.smms.entity.TenantSubscription;
import com.smms.enums.PlanType;
import com.smms.exception.PlanLimitExceededException;
import com.smms.repository.CenterRepository;
import com.smms.repository.UserRepository;
import com.smms.repository.WorkOrderRepository;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** checkTicketLimit(tenantId): explicit tenant, no TenantContext (schedulers). */
class PlanEnforcementTicketLimitForTenantTest {

    private final WorkOrderRepository workOrders = mock(WorkOrderRepository.class);
    private final SubscriptionCache cache = mock(SubscriptionCache.class);
    private final PlanEnforcementService service =
        new PlanEnforcementService(mock(UserRepository.class), workOrders, mock(CenterRepository.class), cache);

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void nullTenantIsNeverLimited() {
        assertThatCode(() -> service.checkTicketLimit((UUID) null)).doesNotThrowAnyException();
        verifyNoInteractions(workOrders);
    }

    @Test
    void usesTheSubscriptionOfTheNamedTenantAndItsOwnTicketCount() {
        UUID tenant = UUID.randomUUID();
        TenantSubscription sub = new TenantSubscription();
        sub.setPlanType(PlanType.FREE);
        when(cache.get(tenant)).thenReturn(Optional.of(sub));
        PlanConfig.PlanLimits limits = PlanConfig.get(PlanType.FREE);
        if (limits.isUnlimited()) return; // nothing to assert for an unlimited free plan
        when(workOrders.countByTenantIdAndDeletedAtIsNull(tenant)).thenReturn((long) limits.maxTickets());
        assertThatThrownBy(() -> service.checkTicketLimit(tenant)).isInstanceOf(PlanLimitExceededException.class);

        when(workOrders.countByTenantIdAndDeletedAtIsNull(tenant)).thenReturn(limits.maxTickets() - 1L);
        assertThatCode(() -> service.checkTicketLimit(tenant)).doesNotThrowAnyException();
    }
}
