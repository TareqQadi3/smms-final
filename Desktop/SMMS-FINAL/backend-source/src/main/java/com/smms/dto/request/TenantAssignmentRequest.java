package com.smms.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/** Body of POST /api/admin/users/{id}/tenant. */
@Data
public class TenantAssignmentRequest {
    @NotNull(message = "tenantId is required")
    private UUID tenantId;
}
