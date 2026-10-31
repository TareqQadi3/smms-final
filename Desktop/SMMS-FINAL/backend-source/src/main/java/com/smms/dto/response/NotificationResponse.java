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
    /** Work order the notification is about; null for notifications without one. */
    UUID workOrderId;
    boolean read;
    Instant createdAt;
}
