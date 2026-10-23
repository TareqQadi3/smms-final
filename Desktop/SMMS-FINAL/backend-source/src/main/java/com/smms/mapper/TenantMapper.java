package com.smms.mapper;

import com.smms.dto.response.TenantResponse;
import com.smms.dto.response.TenantSubscriptionResponse;
import com.smms.entity.Tenant;
import com.smms.entity.TenantSubscription;

/** Explicit field-by-field mapping so entity changes never leak into the API. */
public final class TenantMapper {

    private TenantMapper() {}

    public static TenantResponse toResponse(Tenant t) {
        return new TenantResponse(t.getId(), t.getName(), t.getNameAr(), t.getSlug(), t.isActive(), t.getCreatedAt());
    }

    public static TenantSubscriptionResponse toResponse(TenantSubscription s) {
        return new TenantSubscriptionResponse(s.getTenantId(), s.getPlanType(), s.getStatus(),
            s.getStartDate(), s.getEndDate(), s.getNotes());
    }
}
