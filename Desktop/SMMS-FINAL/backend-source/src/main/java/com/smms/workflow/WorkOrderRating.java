package com.smms.workflow;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Requester rating of a finished work order (V22), at most one per work order. */
@Entity @Table(name = "work_order_ratings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkOrderRating {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(name = "work_order_id", nullable = false, updatable = false) private UUID workOrderId;
    @Column(name = "rated_by_id", nullable = false, updatable = false) private UUID ratedById;
    @Column(nullable = false) private short score;
    @Column(columnDefinition = "TEXT") private String comment;
    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
