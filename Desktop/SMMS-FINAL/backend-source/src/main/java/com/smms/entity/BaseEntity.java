package com.smms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Getter @Setter
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Tenant isolation key — set automatically from TenantContext, never from client.
     * Nullable to allow safe schema migration (backfilled by DataInitializer).
     * NULL means platform-level record (system_admin, or pre-migration data pending backfill).
     */
    @Column(name = "tenant_id")
    private UUID tenantId;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    @Column
    private Instant deletedAt;

    public boolean isDeleted() { return deletedAt != null; }
}
