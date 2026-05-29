package com.smms.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class UpdateCenterRequest {
    private UUID   regionId;
    @Size(max = 200) private String name;
    @Size(max = 200) private String nameAr;
    @Size(max = 20)  private String code;
    private String address;
}
