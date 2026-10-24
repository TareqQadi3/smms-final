package com.smms.dto.response;

import com.smms.enums.UserRole;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class UserResponse {
    UUID id;
    String username;
    String email;
    String fullName;
    String fullNameAr;
    String phone;
    UserRole role;
    boolean active;
    Instant lastLogin;
    CenterSummary center;

    // Resident
    String complexName;
    String buildingNumber;
    String unitNumber;

    // Department
    /** Linked department row (null until linked); the free-text name below stays as display fallback. */
    UUID departmentId;
    String departmentName;
    String departmentSupervisor;
    String departmentEmail;
    String extensionNumber;

    // Custom
    String jobTitle;
}
