package com.smms.pm;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One (plan, due period) generation attempt (V42). Rows are written by native SQL in the repository. */
@Entity
@Table(name = "maintenance_plan_runs")
@Getter @Setter @NoArgsConstructor
public class MaintenancePlanRun {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RunStatus status;

    @Column(nullable = false, length = 10)
    private String source;

    @Column(name = "work_order_id")
    private UUID workOrderId;

    @Column(length = 500)
    private String error;

    @Column(name = "overdue_notified_at")
    private Instant overdueNotifiedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
