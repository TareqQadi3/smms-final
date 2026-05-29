package com.smms.dto.request;

import com.smms.enums.UserRole;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class CreateUserRequest {

    @NotBlank @Size(max = 100)
    private String username;

    @Email @Size(max = 255)
    private String email;               // optional

    @NotBlank @Size(min = 8)
    private String password;

    @NotBlank @Size(max = 200)
    private String fullName;

    @Size(max = 200)
    private String fullNameAr;

    @Size(max = 20)
    private String phone;

    @NotNull
    private UserRole role;

    private UUID regionId;
    private UUID centerId;
    private UUID controlRoomId;         // primary control room link
    private List<UUID> controlRoomIds;  // multiple control rooms

    // ── Resident ──────────────────────────────────────────
    @Size(max = 200) private String complexName;
    @Size(max = 50)  private String buildingNumber;
    @Size(max = 50)  private String unitNumber;

    // ── Department ────────────────────────────────────────
    @Size(max = 200) private String departmentName;
    @Size(max = 200) private String departmentSupervisor;
    @Email @Size(max = 255) private String departmentEmail;
    @Size(max = 20)  private String extensionNumber;

    // ── Custom / Other ────────────────────────────────────
    @Size(max = 200) private String jobTitle;
}
