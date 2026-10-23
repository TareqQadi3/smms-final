package com.smms.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "notifications")
@Slf4j
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Notification {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id")
    private WorkOrder workOrder;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "is_read")
    @Builder.Default
    private boolean read = false;

    /** Defence in depth: always the tenant of the parent, derived in {@link #deriveTenant()}. */
    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void deriveTenant() {
        UUID userTenant = user != null ? user.getTenantId() : null;
        UUID woTenant = workOrder != null ? workOrder.getTenantId() : null;
        if (userTenant != null && woTenant != null && !userTenant.equals(woTenant)) {
            throw new IllegalStateException("cross-tenant notification");
        }
        UUID parent = userTenant != null ? userTenant : woTenant;
        if (tenantId != null && parent != null && !tenantId.equals(parent)) {
            throw new IllegalStateException("notification tenant differs from its recipient/work order tenant");
        }
        if (tenantId == null) tenantId = parent;
        if (tenantId == null && workOrder != null) {
            log.warn("notification row has no tenant: neither recipient nor work order has one (legacy data)");
        }
    }
}
