package com.smms.dto.request;

import com.smms.enums.ControlRoomType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateControlRoomRequest {
    @NotNull  UUID centerId;
    @NotBlank String name;
    @NotBlank String nameAr;
    @NotBlank @Size(max = 20) String code;
    @NotNull  ControlRoomType type;
}
