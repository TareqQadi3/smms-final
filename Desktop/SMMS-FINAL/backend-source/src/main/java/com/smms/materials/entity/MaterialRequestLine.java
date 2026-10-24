package com.smms.materials.entity;

import com.smms.exception.WorkflowException;
import com.smms.procurement.entity.TenantOwnedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One item of a material request. Quantity arithmetic (each step is checked here, the V38 CHECKs are
 * the backstop): approved <= requested; issued <= approved; consumed + returned <= issued;
 * reserved + issued <= approved.
 */
@Entity @Table(name = "material_request_lines")
@Getter @Setter @NoArgsConstructor
public class MaterialRequestLine extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mr_id", nullable = false, updatable = false)
    private MaterialRequest mr;

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(name = "qty_requested", nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal qtyRequested;

    @Column(name = "qty_approved", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyApproved = BigDecimal.ZERO;

    @Column(name = "qty_reserved", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyReserved = BigDecimal.ZERO;

    @Column(name = "qty_issued", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyIssued = BigDecimal.ZERO;

    @Column(name = "qty_consumed", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyConsumed = BigDecimal.ZERO;

    @Column(name = "qty_returned", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyReturned = BigDecimal.ZERO;

    public void approve(BigDecimal q) {
        if (q.signum() < 0 || q.compareTo(qtyRequested) > 0) {
            throw new WorkflowException("Approved quantity must be between 0 and the requested "
                + qtyRequested.toPlainString());
        }
        qtyApproved = q;
    }

    /** Quantity still to be held: approved - issued - reserved. */
    public BigDecimal toReserve() { return qtyApproved.subtract(qtyIssued).subtract(qtyReserved); }

    /** Marks the whole open approved quantity as reserved; returns the delta to reserve in the ledger. */
    public BigDecimal reserveAll() {
        BigDecimal delta = toReserve();
        qtyReserved = qtyReserved.add(delta);
        return delta;
    }

    /** Still to be issued: approved - issued. */
    public BigDecimal toIssue() { return qtyApproved.subtract(qtyIssued); }

    /** Records an issue; returns the part that came out of the reservation. */
    public BigDecimal issue(BigDecimal q) {
        if (q.compareTo(toIssue()) > 0) {
            throw new WorkflowException("Cannot issue " + q.toPlainString() + ": only "
                + toIssue().toPlainString() + " of the approved quantity is left");
        }
        BigDecimal fromReservation = q.min(qtyReserved);
        qtyReserved = qtyReserved.subtract(fromReservation);
        qtyIssued = qtyIssued.add(q);
        return fromReservation;
    }

    /** In the technician's hands: issued - consumed - returned. */
    public BigDecimal outstanding() { return qtyIssued.subtract(qtyConsumed).subtract(qtyReturned); }

    public void consume(BigDecimal q) {
        if (q.compareTo(outstanding()) > 0) {
            throw new WorkflowException("Cannot consume " + q.toPlainString() + ": only "
                + outstanding().toPlainString() + " is outstanding");
        }
        qtyConsumed = qtyConsumed.add(q);
    }

    public void giveBack(BigDecimal q) {
        if (q.compareTo(outstanding()) > 0) {
            throw new WorkflowException("Cannot return " + q.toPlainString() + ": only "
                + outstanding().toPlainString() + " is outstanding");
        }
        qtyReturned = qtyReturned.add(q);
    }

    /** Drops the held reservation; returns what to release in the ledger. */
    public BigDecimal releaseReservation() {
        BigDecimal r = qtyReserved;
        qtyReserved = BigDecimal.ZERO;
        return r;
    }

    public boolean fullyIssued() { return qtyIssued.compareTo(qtyApproved) >= 0; }
}
