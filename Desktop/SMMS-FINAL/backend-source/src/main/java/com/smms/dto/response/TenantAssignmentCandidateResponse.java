package com.smms.dto.response;

import com.smms.enums.UserRole;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/** A non-admin user without a tenant (cannot sign in). Ids only, no email or name. */
@Data @Builder
public class TenantAssignmentCandidateResponse {
    UUID id;
    String username;
    UserRole role;
    UUID centerId;
    UUID regionId;
}
