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
    String fullName;
    UUID centerId;
}
