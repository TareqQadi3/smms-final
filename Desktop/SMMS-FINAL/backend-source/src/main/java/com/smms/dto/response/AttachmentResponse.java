package com.smms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** Attachment metadata only: no server file path and no entity graph. */
@Data @Builder
public class AttachmentResponse {
    UUID id;
    String fileName;
    Integer fileSize;
    String mimeType;
    Instant createdAt;
    UUID uploadedById;
    /** "before", "after" or null (general attachment). */
    String photoPhase;
}
