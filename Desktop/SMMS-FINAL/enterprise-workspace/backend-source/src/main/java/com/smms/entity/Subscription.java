package com.smms.entity;

import com.smms.enums.SubscriptionPlan;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "subscriptions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Subscription extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "center_id", unique = true, nullable = false)
    private Center center;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionPlan plan;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Null only for ENTERPRISE plans with no expiry */
    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "max_users")
    @Builder.Default
    private int maxUsers = 20;

    /** -1 = unlimited */
    @Column(name = "max_work_orders_per_month")
    @Builder.Default
    private int maxWorkOrdersPerMonth = -1;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;

    @Column(length = 1000)
    private String notes;

    // ── Business helpers ────────────────────────────────────

    public boolean isExpired() {
        return endDate != null && LocalDate.now().isAfter(endDate);
    }

    /** Active and not expired and not soft-deleted */
    public boolean isValid() {
        return isActive && !isExpired() && getDeletedAt() == null;
    }

    /** Returns days remaining, 0 if already expired, -1 if no expiry */
    public long daysRemaining() {
        if (endDate == null) return -1;
        if (isExpired()) return 0;
        return ChronoUnit.DAYS.between(LocalDate.now(), endDate);
    }
}
