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

    /** LEGACY (V31 comment): stock lives in stock_balances; never written by the application. */
    @Column(nullable = false, precision = 12, scale = 2, insertable = false, updatable = false)
    private BigDecimal quantity;

    @Column(name = "reorder_level", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal reorderLevel = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uom_id")
    private com.smms.inventory.entity.UnitOfMeasure uom;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /** Set when a low-stock notification was sent; cleared when stock rises above the reorder level. */
    @Column(name = "low_stock_notified_at")
    private java.time.Instant lowStockNotifiedAt;

    @Column(length = 80)
    private String category;
}

