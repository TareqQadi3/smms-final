package com.smms.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One login. The refresh token carries this id ("sid") and a random jti; only sha-256(jti) is stored.
 * Rotation moves current to previous, so a replayed (previous or unknown) jti proves token theft.
 */
@Entity @Table(name = "auth_sessions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuthSession {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "current_jti_hash", nullable = false, length = 64)
    private String currentJtiHash;

    @Column(name = "previous_jti_hash", length = 64)
    private String previousJtiHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_refreshed_at")
    private Instant lastRefreshedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 40)
    private String revokeReason;

    public boolean isRevoked() { return revokedAt != null; }
}
