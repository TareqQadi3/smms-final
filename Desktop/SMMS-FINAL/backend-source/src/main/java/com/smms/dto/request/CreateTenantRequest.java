package com.smms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateTenantRequest {
    @NotBlank @Size(max = 200)
    private String name;

    @Size(max = 200)
    private String nameAr;

    /** Optional URL-safe identifier. */
    @Pattern(regexp = "^[a-z0-9-]{2,50}$", message = "slug must be 2-50 characters: lowercase letters, digits or hyphens")
    private String slug;
}
