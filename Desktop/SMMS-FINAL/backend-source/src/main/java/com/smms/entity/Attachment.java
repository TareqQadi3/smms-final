package com.smms.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "attachments")
@Slf4j
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Attachment {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id", nullable = false)
    private WorkOrder workOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_id", nullable = false)
    private User uploadedBy;

    @Column(name = "file_name", nullable = false, length = 300)
    private String fileName;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "file_size")
    private Integer fileSize;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    /** Idempotent upload request that created this row (null for uploads without Idempotency-Key). */
    @Column(name = "upload_request_id")
    private UUID uploadRequestId;

    /** "before" / "after" photo of the work (V25); null for a general attachment. */
    @Column(name = "photo_phase", length = 10)
    private String photoPhase;

    /** Defence in depth: always the tenant of the parent, derived in {@link #deriveTenant()}. */
    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void deriveTenant() {
        UUID parent = workOrder != null ? workOrder.getTenantId() : null;
        if (tenantId != null && parent != null && !tenantId.equals(parent)) {
            throw new IllegalStateException("attachment tenant differs from its work order tenant");
        }
        if (tenantId == null) tenantId = parent;
        if (tenantId == null) log.warn("attachment row has no tenant: its work order has none (legacy data)");
    }
}
