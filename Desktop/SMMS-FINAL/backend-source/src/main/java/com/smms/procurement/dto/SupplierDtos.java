package com.smms.procurement.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class SupplierDtos {
    private SupplierDtos() {}

    /** tenantId is honoured only for the platform administrator (no tenant context). */
    public record SupplierRequest(
        UUID tenantId,
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 200) String nameAr,
        @Size(max = 40) String phone,
        @Email @Size(max = 255) String email,
        @Size(max = 40) String taxNumber) {}

    public record SupplierResponse(UUID id, String code, String name, String nameAr, String phone, String email,
                                   String taxNumber, boolean active, Instant createdAt) {}
}
