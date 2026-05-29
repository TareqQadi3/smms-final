package com.smms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data @Builder
public class SubscriptionResponse {

    private UUID id;

    // Center info
    private UUID centerId;
    private String centerName;
    private String centerNameAr;
    private String centerCode;

    // Plan details
    private String plan;
    private String planLabelAr;
    private String planLabelEn;

    // Dates
    private LocalDate startDate;
    private LocalDate endDate;

    // Limits
    private int maxUsers;
    private int maxWorkOrdersPerMonth;

    // Status
    private boolean active;
    private boolean expired;
    private boolean valid;
    private long daysRemaining;

    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
}
