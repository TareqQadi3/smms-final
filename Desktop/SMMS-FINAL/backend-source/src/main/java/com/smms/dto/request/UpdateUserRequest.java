package com.smms.dto.request;

import com.smms.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class UpdateUserRequest {
    @Size(max = 200) private String fullName;
    @Size(max = 200) private String fullNameAr;
    @Email @Size(max = 255) private String email;
    @Size(max = 20)  private String phone;
    private UserRole role;
    private UUID centerId;
    private UUID regionId;
    /** Needed when the role becomes department and the account has no department name yet. */
    @Size(max = 200) private String departmentName;
    @Email @Size(max = 255) private String departmentEmail;
}
