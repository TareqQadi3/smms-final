package com.smms.dto.response;

import com.smms.assets.AssetCriticality;
import com.smms.enums.AssetStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data @Builder
public class AssetResponse {
    private UUID id;
    private String assetCode;
    private String name;
    private String nameAr;
    private String category;
    private String manufacturer;
    private String model;
    private String serialNumber;
    private String location;
    private AssetStatus status;
    private AssetCriticality criticality;
    private LocalDate installDate;
    private LocalDate warrantyExpiry;
    private Instant decommissionedAt;
    private String decommissionReason;
    private CenterSummary center;
    private DepartmentResponse department;
    /** Open (not completed/closed/cancelled) work orders of the asset that the caller may read. */
    private long openWorkOrders;
    private boolean activeDowntime;
    private Long version;
}
