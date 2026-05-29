package com.smms.dto.response;

import lombok.Builder;
import lombok.Data;
import java.util.UUID;

@Data @Builder
public class CenterResponse {
    private UUID   id;
    private String name;
    private String nameAr;
    private String code;
    private String address;
    private boolean active;
    private RegionInfo region;

    @Data @Builder
    public static class RegionInfo {
        private UUID   id;
        private String name;
        private String nameAr;
        private String code;
    }
}
