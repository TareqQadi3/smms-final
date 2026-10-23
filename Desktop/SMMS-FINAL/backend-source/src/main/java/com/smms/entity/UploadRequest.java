package com.smms.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Idempotency record of one attachment upload request (see V12). Rows are inserted natively. */
@Entity @Table(name = "upload_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UploadRequest {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "idem_key", nullable = false, length = 64)
    private String idemKey;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
