package com.smms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateRegionRequest {
    @NotBlank String name;
    @NotBlank String nameAr;
    @NotBlank @Size(max = 20) String code;
}
