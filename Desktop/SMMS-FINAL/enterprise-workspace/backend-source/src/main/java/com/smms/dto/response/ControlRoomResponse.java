package com.smms.dto.response;

import com.smms.enums.ControlRoomType;
import lombok.Builder;
import lombok.Data;
import java.util.UUID;

@Data @Builder
public class ControlRoomResponse {
    private UUID            id;
    private String          name;
    private String          nameAr;
    private String          code;
    private ControlRoomType type;
    private boolean         active;
    private CenterInfo      center;

    @Data @Builder
    public static class CenterInfo {
        private UUID   id;
        private String name;
        private String nameAr;
    }
}
