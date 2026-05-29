package com.smms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class NotificationResponse {
    UUID id;
    String type;
    String title;
    String body;
    boolean read;
    Instant createdAt;
}
