package com.smms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data @Builder
public class CenterSummary {
    UUID id;
    String name;
    String nameAr;
    String code;
}
