package com.smms.entity;

import com.smms.enums.InventoryMovementType;
import com.smms.inventory.api.StockLedger.MovementKind;
import com.smms.inventory.entity.Warehouse;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/** One immutable ledger row (V33/V34: the database rejects UPDATE and DELETE). */
@Entity
@Table(name = "inventory_movements",
    indexes = @Index(name = "idx_inventory_movements_item", columnList = "item_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InventoryMovement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false, updatable = false)
    private InventoryItem item;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", updatable = false)
    private Warehouse warehouse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id", updatable = false)
    private WorkOrder workOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", updatable = false)
    private User actor;

    /** Legacy coarse direction: stock_in for inbound kinds, stock_out for outbound kinds. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "inventory_movement_type", updatable = false)
    private InventoryMovementType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_kind", length = 20, updatable = false)
    private MovementKind movementKind;

    @Column(nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal quantity;

    @Column(name = "balance_after", precision = 14, scale = 3, updatable = false)
    private BigDecimal balanceAfter;

    @Column(name = "reason_code", length = 30, updatable = false) private String reasonCode;
    @Column(name = "reversal_of_id", updatable = false) private UUID reversalOfId;
    @Column(name = "material_request_line_id", updatable = false) private UUID materialRequestLineId;
    @Column(name = "po_receipt_line_id", updatable = false) private UUID poReceiptLineId;
    @Column(length = 120, updatable = false) private String reference;
    @Column(name = "idempotency_record_id", updatable = false) private UUID idempotencyRecordId;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String note;

    /** Insert order (identity); orders the stock card. Read only. */
    @Column(insertable = false, updatable = false)
    private Long seq;
}
