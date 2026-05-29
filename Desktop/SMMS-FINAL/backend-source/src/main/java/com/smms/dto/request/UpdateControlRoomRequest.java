package com.smms.dto.request;

import com.smms.enums.ControlRoomType;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class UpdateControlRoomRequest {
    private UUID centerId;
    @Size(max = 200) private String name;
    @Size(max = 200) private String nameAr;
    @Size(max = 20)  private String code;
    private ControlRoomType type;
}
