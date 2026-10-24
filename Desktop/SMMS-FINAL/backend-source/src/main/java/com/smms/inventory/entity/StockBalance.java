package com.smms.inventory.entity;

import com.smms.entity.InventoryItem;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** On-hand and reserved quantity of one item in one warehouse (V32). Changed only under a row lock. */
@Entity @Table(name = "stock_balances")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StockBalance {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false, updatable = false)
    private InventoryItem item;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false, updatable = false)
    private Warehouse warehouse;

    @Column(name = "on_hand", nullable = false, precision = 14, scale = 3)
    @Builder.Default private BigDecimal onHand = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 3)
    @Builder.Default private BigDecimal reserved = BigDecimal.ZERO;

    @Column(name = "min_qty", precision = 14, scale = 3)
    private BigDecimal minQty;

    @Version
    private long version;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public BigDecimal available() { return onHand.subtract(reserved); }
}
