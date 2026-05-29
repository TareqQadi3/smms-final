package com.smms.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "inventory_items",
    uniqueConstraints = @UniqueConstraint(columnNames = {"center_id", "sku"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InventoryItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "center_id", nullable = false)
    private Center center;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "name_ar", nullable = false, length = 200)
    private String nameAr;

    @Column(nullable = false, length = 80)
    private String sku;

    @Column(length = 40)
    private String unit;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "reorder_level", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal reorderLevel = BigDecimal.ZERO;
}

