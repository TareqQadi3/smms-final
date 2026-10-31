package com.smms.dto.request;

import com.smms.assets.AssetCriticality;
import com.smms.enums.AssetStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
public class CreateAssetRequest {
    @NotNull private UUID centerId;
    private UUID departmentId;
    @NotBlank @Size(max = 200) private String name;
    @NotBlank @Size(max = 200) private String nameAr;
    @Size(max = 60) private String assetCode;
    @Size(max = 80) private String category;
    @Size(max = 120) private String manufacturer;
    @Size(max = 120) private String model;
    @Size(max = 120) private String serialNumber;
    @Size(max = 300) private String location;
    private LocalDate installDate;
    private LocalDate warrantyExpiry;
    private AssetCriticality criticality;
    private AssetStatus status = AssetStatus.active;
}
