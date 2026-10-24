package com.smms.workflow;

import com.smms.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Read model of an escalation row (V21). Rows are written with {@link EscalationRepository#insertIgnore}. */
@Entity @Table(name = "work_order_escalations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkOrderEscalation {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(name = "work_order_id", nullable = false, updatable = false) private UUID workOrderId;
    @Column(nullable = false, updatable = false) private short level;
    @Column(name = "trigger_kind", nullable = false, updatable = false, length = 20) private String triggerKind;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "triggered_by_id", updatable = false) private User triggeredBy;
    @Column(columnDefinition = "TEXT", updatable = false) private String reason;
    @Column(name = "notified_count") private Integer notifiedCount;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
}
