package com.smms.inventory.entity;

import com.smms.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/** Tenant-level unit of measure (V31); {@code decimals} caps the quantity precision of its items. */
@Entity @Table(name = "units_of_measure")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UnitOfMeasure extends BaseEntity {

    @Column(nullable = false, length = 20) private String code;
    @Column(nullable = false, length = 100) private String name;
    @Column(name = "name_ar", nullable = false, length = 100) private String nameAr;
    @Column(nullable = false) private short decimals;

    @Column(name = "is_active", nullable = false)
    @Builder.Default private boolean active = true;
}
