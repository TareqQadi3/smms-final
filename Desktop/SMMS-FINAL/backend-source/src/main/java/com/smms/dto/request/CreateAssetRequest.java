package com.smms.dto.request;

import com.smms.enums.AssetStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateAssetRequest {
    @NotNull private UUID centerId;
    private UUID departmentId;
    @NotBlank @Size(max = 200) private String name;
    @NotBlank @Size(max = 200) private String nameAr;
    @Size(max = 120) private String serialNumber;
    @Size(max = 300) private String location;
    private AssetStatus status = AssetStatus.active;
}

