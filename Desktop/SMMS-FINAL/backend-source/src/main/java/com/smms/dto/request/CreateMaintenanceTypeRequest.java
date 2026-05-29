package com.smms.dto.request;

import com.smms.enums.ControlRoomType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateMaintenanceTypeRequest {
    @NotBlank @Size(max = 200)
    private String name;

    @NotBlank @Size(max = 200)
    private String nameAr;

    @NotNull
    private ControlRoomType controlRoomType;
}
