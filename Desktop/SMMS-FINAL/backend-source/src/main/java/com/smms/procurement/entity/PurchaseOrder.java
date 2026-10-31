package com.smms.procurement.entity;

import com.smms.entity.Center;
import com.smms.entity.User;
import com.smms.exception.WorkflowException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity @Table(name = "purchase_orders")
@Getter @Setter @NoArgsConstructor
public class PurchaseOrder extends TenantOwnedEntity {

    /** Filled by the V36 trigger; read back with refresh after the first flush. */
    @Column(name = "po_number", insertable = false, updatable = false)
    private String poNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "center_id", nullable = false, updatable = false)
    private Center center;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "work_order_id")
    private UUID workOrderId;

    @Column(name = "material_request_id")
    private UUID materialRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseOrderStatus status = PurchaseOrderStatus.draft;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false, updatable = false)
    private User createdBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_id")
    private User approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String decisionReason;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Version
    @Column(nullable = false)
    private Long version;

    @org.hibernate.annotations.BatchSize(size = 50)
    @OneToMany(mappedBy = "po", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    /** Moves to {@code target} or fails with a 422 naming the illegal step. */
    public void transitionTo(PurchaseOrderStatus target) {
        if (!status.canMoveTo(target)) {
            throw new WorkflowException("Purchase order cannot move from " + status + " to " + target);
        }
        status = target;
    }

    /** Like {@link #transitionTo} but staying in the same state is allowed (a second partial receipt). */
    public void transitionOrStay(PurchaseOrderStatus target) {
        if (status != target) transitionTo(target);
    }

    /** Sum of qty x price over priced lines. */
    public BigDecimal total() {
        BigDecimal t = BigDecimal.ZERO;
        for (PurchaseOrderLine l : lines) {
            if (l.getUnitPrice() != null) t = t.add(l.getUnitPrice().multiply(l.getQtyOrdered()));
        }
        return t;
    }

    public boolean fullyReceived() {
        return !lines.isEmpty() && lines.stream().allMatch(l -> l.getQtyReceived().compareTo(l.getQtyOrdered()) >= 0);
    }
}
