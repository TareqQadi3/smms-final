package com.smms.dto.response;

import com.smms.enums.AssetStatus;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data @Builder
public class AssetResponse {
    private UUID id;
    private String name;
    private String nameAr;
    private String serialNumber;
    private String location;
    private AssetStatus status;
    private CenterSummary center;
    private DepartmentResponse department;
}

