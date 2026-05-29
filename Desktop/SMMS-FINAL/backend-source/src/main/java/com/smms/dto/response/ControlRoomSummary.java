package com.smms.dto.response;

import com.smms.enums.ControlRoomType;
import lombok.Builder;
import lombok.Data;
import java.util.UUID;

@Data @Builder
public class ControlRoomSummary {
    private UUID            id;
    private String          name;
    private String          nameAr;
    private String          code;
    private ControlRoomType type;
}
