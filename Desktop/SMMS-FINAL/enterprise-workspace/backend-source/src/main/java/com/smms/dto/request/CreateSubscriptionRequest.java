package com.smms.dto.request;

import com.smms.enums.SubscriptionPlan;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
public class CreateSubscriptionRequest {

    @NotNull(message = "centerId is required")
    private UUID centerId;

    @NotNull(message = "plan is required")
    private SubscriptionPlan plan;

    /** If null, defaults to today */
    private LocalDate startDate;

    /** If null, computed from plan.defaultDurationDays */
    private LocalDate endDate;

    /** If null, defaults to plan.defaultMaxUsers */
    private Integer maxUsers;

    /** If null, defaults to plan.defaultMaxWorkOrdersPerMonth */
    private Integer maxWorkOrdersPerMonth;

    private String notes;
}
