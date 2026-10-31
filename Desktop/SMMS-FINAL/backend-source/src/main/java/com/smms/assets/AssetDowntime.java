package com.smms.assets;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** One downtime period of an asset (V43). */
@Entity
@Table(name = "asset_downtime")
@Getter @Setter @NoArgsConstructor
public class AssetDowntime {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;

    @Column(name = "work_order_id")
    private UUID workOrderId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "recorded_by_id", nullable = false, updatable = false)
    private UUID recordedById;

    @Column(name = "ended_by_id")
    private UUID endedById;

    @Column(name = "marked_asset", nullable = false)
    private boolean markedAsset;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
