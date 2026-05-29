package com.smms.dto.response;

import com.smms.enums.UserRole;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data @Builder
public class UserSummary {
    UUID id;
    String username;
    String fullName;
    UserRole role;
}
