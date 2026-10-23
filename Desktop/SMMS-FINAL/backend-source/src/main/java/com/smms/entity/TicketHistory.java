package com.smms.entity;

import com.smms.converter.TicketStatusType;
import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "ticket_history")
@Slf4j
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TicketHistory {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id", nullable = false)
    private WorkOrder workOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "actor_role", nullable = false, columnDefinition = "user_role")
    private UserRole actorRole;

    @Type(TicketStatusType.class)
    @Column(name = "from_status", columnDefinition = "ticket_status")
    private TicketStatus fromStatus;

    @Type(TicketStatusType.class)
    @Column(name = "to_status", nullable = false, columnDefinition = "ticket_status")
    private TicketStatus toStatus;

    @Column(length = 50)
    private String stage;

    @Column(columnDefinition = "TEXT")
    private String note;

    /** Defence in depth: always the tenant of the parent, derived in {@link #deriveTenant()}. */
    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void deriveTenant() {
        UUID parent = workOrder != null ? workOrder.getTenantId() : null;
        if (tenantId != null && parent != null && !tenantId.equals(parent)) {
            throw new IllegalStateException("ticket_history tenant differs from its work order tenant");
        }
        if (tenantId == null) tenantId = parent;
        if (tenantId == null) log.warn("ticket_history row has no tenant: its work order has none (legacy data)");
    }
}
