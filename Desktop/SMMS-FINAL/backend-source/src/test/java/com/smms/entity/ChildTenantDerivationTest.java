package com.smms.entity;

import com.smms.enums.UserRole;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** @PrePersist tenant derivation on the three child entities. */
class ChildTenantDerivationTest {

    private static final UUID T1 = UUID.randomUUID();
    private static final UUID T2 = UUID.randomUUID();

    private static WorkOrder wo(UUID tenant) {
        WorkOrder w = new WorkOrder();
        w.setId(UUID.randomUUID());
        w.setTenantId(tenant);
        return w;
    }

    private static User user(UserRole role, UUID tenant) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        u.setTenantId(tenant);
        return u;
    }

    @Test
    void historyAndAttachmentDeriveTenantFromWorkOrder() {
        TicketHistory h = TicketHistory.builder().workOrder(wo(T1)).build();
        h.deriveTenant();
        assertThat(h.getTenantId()).isEqualTo(T1);

        Attachment a = Attachment.builder().workOrder(wo(T2)).build();
        a.deriveTenant();
        assertThat(a.getTenantId()).isEqualTo(T2);
    }

    @Test
    void presetTenantMatchingParentIsKept() {
        TicketHistory h = TicketHistory.builder().workOrder(wo(T1)).tenantId(T1).build();
        h.deriveTenant();
        assertThat(h.getTenantId()).isEqualTo(T1);

        Notification n = Notification.builder().user(user(UserRole.control_room, T1)).tenantId(T1).build();
        n.deriveTenant();
        assertThat(n.getTenantId()).isEqualTo(T1);
    }

    @Test
    void presetTenantDifferentFromParentIsRejected() {
        TicketHistory h = TicketHistory.builder().workOrder(wo(T1)).tenantId(T2).build();
        assertThatThrownBy(h::deriveTenant).isInstanceOf(IllegalStateException.class);

        Attachment a = Attachment.builder().workOrder(wo(T1)).tenantId(T2).build();
        assertThatThrownBy(a::deriveTenant).isInstanceOf(IllegalStateException.class);

        Notification n = Notification.builder().user(user(UserRole.control_room, T1)).tenantId(T2).build();
        assertThatThrownBy(n::deriveTenant).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void notificationRecipientAndWorkOrderInDifferentTenantsIsRejected() {
        Notification n = Notification.builder()
            .user(user(UserRole.control_room, T1)).workOrder(wo(T2)).build();
        assertThatThrownBy(n::deriveTenant)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cross-tenant");
    }

    @Test
    void adminRecipientTakesWorkOrderTenant() {
        Notification n = Notification.builder()
            .user(user(UserRole.system_admin, null)).workOrder(wo(T2)).build();
        n.deriveTenant();
        assertThat(n.getTenantId()).isEqualTo(T2);
    }

    @Test
    void recipientTenantUsedWhenThereIsNoWorkOrder() {
        Notification n = Notification.builder().user(user(UserRole.control_room, T1)).build();
        n.deriveTenant();
        assertThat(n.getTenantId()).isEqualTo(T1);
    }

    @Test
    void legacyNullTenantStaysNullAndNeverDefaults() {
        TicketHistory h = TicketHistory.builder().workOrder(wo(null)).build();
        h.deriveTenant();
        assertThat(h.getTenantId()).isNull();

        Attachment a = Attachment.builder().workOrder(wo(null)).build();
        a.deriveTenant();
        assertThat(a.getTenantId()).isNull();

        Notification n = Notification.builder().user(user(UserRole.system_admin, null)).workOrder(wo(null)).build();
        n.deriveTenant();
        assertThat(n.getTenantId()).isNull();

        Notification platform = Notification.builder().user(user(UserRole.system_admin, null)).build();
        platform.deriveTenant();
        assertThat(platform.getTenantId()).isNull();
    }
}
