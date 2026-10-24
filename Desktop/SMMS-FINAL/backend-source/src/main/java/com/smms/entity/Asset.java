package com.smms.entity;

import com.smms.assets.AssetCriticality;
import com.smms.enums.AssetStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "assets",
    indexes = @Index(name = "idx_assets_center", columnList = "center_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Asset extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "center_id", nullable = false)
    private Center center;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "name_ar", nullable = false, length = 200)
    private String nameAr;

    @Column(name = "serial_number", length = 120)
    private String serialNumber;

    @Column(length = 300)
    private String location;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "asset_status")
    @Builder.Default
    private AssetStatus status = AssetStatus.active;

    // ── Asset register columns (V40) ──────────────────────────

    @Column(name = "asset_code", length = 60)
    private String assetCode;

    @Column(length = 80)
    private String category;

    @Column(length = 120)
    private String manufacturer;

    @Column(length = 120)
    private String model;

    @Column(name = "install_date")
    private LocalDate installDate;

    @Column(name = "warranty_expiry")
    private LocalDate warrantyExpiry;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private AssetCriticality criticality;

    @Column(name = "decommissioned_at")
    private Instant decommissionedAt;

    @Column(name = "decommission_reason", columnDefinition = "TEXT")
    private String decommissionReason;

    /** Optimistic lock (wrapper type: a new row has a null version). */
    @Version
    @Column(nullable = false)
    private Long version;

    public boolean isDecommissioned() { return decommissionedAt != null; }
}
