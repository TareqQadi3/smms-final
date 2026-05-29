package com.smms.dto.response;

import com.smms.enums.UserRole;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data @Builder
public class AuthResponse {
    String token;
    String refreshToken;
    UserRole role;
    UUID userId;
    String username;
    String fullName;
    String fullNameAr;
    UUID centerId;
    String centerName;
    String centerNameAr;
    UUID tenantId;
    String tenantName;
}
