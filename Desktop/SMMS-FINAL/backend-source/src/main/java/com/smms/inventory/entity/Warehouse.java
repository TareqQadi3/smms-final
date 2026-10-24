package com.smms.inventory.entity;

import com.smms.entity.BaseEntity;
import com.smms.entity.Center;
import jakarta.persistence.*;
import lombok.*;

/** A stock location of a center (V30). Deactivated, never deleted. */
@Entity @Table(name = "warehouses")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Warehouse extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "center_id", nullable = false, updatable = false)
    private Center center;

    @Column(nullable = false, length = 40) private String code;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "name_ar", nullable = false, length = 200) private String nameAr;

    @Column(name = "is_active", nullable = false)
    @Builder.Default private boolean active = true;
}
