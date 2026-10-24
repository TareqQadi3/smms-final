package com.smms.procurement.entity;

import com.smms.exception.WorkflowException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity @Table(name = "purchase_order_lines")
@Getter @Setter @NoArgsConstructor
public class PurchaseOrderLine extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "po_id", nullable = false, updatable = false)
    private PurchaseOrder po;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(name = "qty_ordered", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyOrdered;

    @Column(name = "qty_received", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyReceived = BigDecimal.ZERO;

    @Column(name = "unit_price", precision = 14, scale = 2)
    private BigDecimal unitPrice;

    public BigDecimal remaining() { return qtyOrdered.subtract(qtyReceived); }

    /** Over-receipt prevention: receiving more than is outstanding is a 422. */
    public void receive(BigDecimal q) {
        if (q.compareTo(remaining()) > 0) {
            throw new WorkflowException("Over-receipt on line " + lineNo + ": outstanding " + remaining().toPlainString()
                + ", received " + q.toPlainString());
        }
        qtyReceived = qtyReceived.add(q);
    }
}
