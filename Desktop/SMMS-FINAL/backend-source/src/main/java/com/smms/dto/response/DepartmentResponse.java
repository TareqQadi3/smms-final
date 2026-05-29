package com.smms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data @Builder
public class DepartmentResponse {
    private UUID id;
    private String name;
    private String nameAr;
    private String code;
    private boolean active;
    private CenterSummary center;
}

