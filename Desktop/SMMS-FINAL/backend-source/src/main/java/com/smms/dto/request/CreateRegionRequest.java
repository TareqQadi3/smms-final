package com.smms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateRegionRequest {
    @NotBlank String name;
    @NotBlank String nameAr;
    @NotBlank @Size(max = 20) String code;

    /** Platform admin only: tenant that will own the region. Ignored for tenant-scoped admins. */
    java.util.UUID tenantId;
}
