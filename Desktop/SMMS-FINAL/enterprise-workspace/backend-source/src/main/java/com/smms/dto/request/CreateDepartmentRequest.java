package com.smms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateDepartmentRequest {
    @NotNull private UUID centerId;
    @NotBlank @Size(max = 200) private String name;
    @NotBlank @Size(max = 200) private String nameAr;
    @NotBlank @Size(max = 40) private String code;
}

