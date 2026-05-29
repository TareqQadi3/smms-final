package com.smms.dto.response;

import com.smms.enums.ControlRoomType;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data @Builder
public class MaintenanceTypeResponse {
    private UUID id;
    private String name;
    private String nameAr;
    private ControlRoomType controlRoomType;
    private boolean active;
}
