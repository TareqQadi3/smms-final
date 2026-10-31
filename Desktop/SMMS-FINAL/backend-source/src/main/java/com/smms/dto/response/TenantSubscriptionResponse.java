package com.smms.dto.response;

import com.smms.enums.PlanType;
import com.smms.enums.TenantStatus;

import java.time.Instant;
import java.util.UUID;

/** Subscription view for the platform admin; internal ids and audit columns are not exposed. */
public record TenantSubscriptionResponse(UUID tenantId, PlanType planType, TenantStatus status,
                                         Instant startDate, Instant endDate, String notes) {}
