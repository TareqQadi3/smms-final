package com.smms.workflow;

import com.smms.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** One request for information (V20): question, answer or cancellation, and the SLA pause it caused. */
@Entity @Table(name = "work_order_info_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InfoRequest {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private UUID workOrderId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_id", nullable = false, updatable = false)
    private User requestedBy;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "answered_by_id")
    private User answeredBy;

    @Column(columnDefinition = "TEXT")
    private String answer;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "paused_seconds")
    private Long pausedSeconds;

    @CreationTimestamp @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp @Column(name = "updated_at")
    private Instant updatedAt;
}
