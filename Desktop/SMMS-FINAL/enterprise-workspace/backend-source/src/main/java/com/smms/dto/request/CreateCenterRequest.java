package com.smms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateCenterRequest {
    @NotNull UUID regionId;
    @NotBlank String name;
    @NotBlank String nameAr;
    @NotBlank @Size(max = 20) String code;
    String address;
}
