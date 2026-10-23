package com.smms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data @Builder
public class TenantAssignmentResponse {
    UUID userId;
    UUID tenantId;
}
