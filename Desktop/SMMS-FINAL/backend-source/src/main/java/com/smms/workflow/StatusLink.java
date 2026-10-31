package com.smms.workflow;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Public status link (V23): only the SHA-256 of the token is stored. */
@Entity @Table(name = "work_order_status_links")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StatusLink {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(name = "work_order_id", nullable = false, updatable = false) private UUID workOrderId;
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64) private String tokenHash;
    @Column(name = "created_by_id", nullable = false, updatable = false) private UUID createdById;
    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "revoked_at") private Instant revokedAt;
}
