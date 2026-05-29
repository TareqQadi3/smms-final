package com.smms.entity;

import com.smms.converter.TicketStatusType;
import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "ticket_history")
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

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
