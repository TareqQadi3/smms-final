package com.smms.idempotency;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/** One idempotent request, unique per (user, scope, key); table idempotency_records (V14). */
@Entity @Table(name = "idempotency_records")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class IdempotencyRecord implements Persistable<UUID> {

    @Id
    private UUID id;

    /** Null for platform-admin callers. */
    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 60, updatable = false)
    private String scope;

    @Column(name = "idem_key", nullable = false, length = 64, updatable = false)
    private String idemKey;

    @Column(nullable = false, length = 64, updatable = false)
    private String fingerprint;

    /** Id of the object the operation produced (replay loads the response from it). */
    @Column(name = "result_ref")
    private UUID resultRef;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** The id is assigned by the service, so tell Spring Data to INSERT (no select-before-merge). */
    @Transient
    @Builder.Default
    private boolean fresh = true;

    @Override public boolean isNew() { return fresh; }

    @PostPersist @PostLoad
    void markNotNew() { this.fresh = false; }
}
