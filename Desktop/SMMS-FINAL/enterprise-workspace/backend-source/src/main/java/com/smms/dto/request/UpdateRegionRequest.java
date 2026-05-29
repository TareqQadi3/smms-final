package com.smms.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateRegionRequest {
    @Size(max = 200) private String name;
    @Size(max = 200) private String nameAr;
    @Size(max = 20)  private String code;
    private String description;
}
