package com.smms.dto.request;

import com.smms.enums.SubscriptionPlan;
import lombok.Data;

import java.time.LocalDate;

@Data
public class UpdateSubscriptionRequest {
    private SubscriptionPlan plan;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer maxUsers;
    private Integer maxWorkOrdersPerMonth;
    private Boolean active;
    private String notes;
}
