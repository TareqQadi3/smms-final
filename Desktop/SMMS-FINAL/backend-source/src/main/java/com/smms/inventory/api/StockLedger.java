package com.smms.inventory.api;

import com.smms.entity.User;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Contract of the stock ledger. Implemented by the inventory module ({@code StockLedgerService});
 * called by procurement and material requests. Every method joins an existing transaction
 * ({@code MANDATORY}): the work-order / material-request / purchase-order change and the ledger rows
 * must commit together. Stock never goes negative: a shortfall raises a 409 conflict exception.
 *
 * <p>Lock order: callers lock their header rows (material request, purchase order) BEFORE calling
 * the ledger, which locks the {@code (item, warehouse)} balance rows sorted by item then warehouse.
 */
public interface StockLedger {

    /** What the caller wants to do with a warehouse. */
    enum Access { READ, OPERATE }

    /** Ledger movement kinds (V33 {@code movement_kind}). */
    enum MovementKind {
        opening, receipt, issue, return_in, adjustment_in, adjustment_out, reversal_in, reversal_out;

        /** True for kinds that increase on-hand. */
        public boolean isInbound() {
            return this == opening || this == receipt || this == return_in
                || this == adjustment_in || this == reversal_in;
        }
    }

    record WarehouseRef(UUID id, String code, String name, String nameAr) {}

    record ItemRef(UUID id, String sku, String name, String nameAr, String uom) {}

    /** A ledger row as returned by {@link #apply}. */
    record MovementRef(UUID id, MovementKind kind, UUID itemId, UUID warehouseId,
                       BigDecimal quantity, BigDecimal balanceAfter) {}

    /** One stock change. {@code qty} is always positive; the kind gives the direction. */
    record StockCommand(UUID tenantId, UUID warehouseId, UUID itemId, MovementKind kind, BigDecimal qty,
                        boolean consumeReservation, UUID workOrderId, UUID materialRequestLineId,
                        UUID poReceiptLineId, UUID reversalOfId, String reasonCode, String note,
                        String reference, User actor, UUID idempotencyRecordId) {}

    /** Warehouse in the actor's scope; 404 when out of scope. */
    @Transactional(propagation = Propagation.MANDATORY)
    WarehouseRef requireWarehouse(UUID warehouseId, User actor, Access access);

    /** Active item of that center (and tenant); 404 otherwise. */
    @Transactional(propagation = Propagation.MANDATORY)
    ItemRef requireItem(UUID itemId, UUID centerId);

    /** Locks the balances in sorted order, checks availability, writes the ledger rows. */
    @Transactional(propagation = Propagation.MANDATORY)
    List<MovementRef> apply(List<StockCommand> cmds);

    /** Reserve {@code qty}; needs {@code on_hand - reserved >= qty}, else 409 InsufficientStockException. */
    @Transactional(propagation = Propagation.MANDATORY)
    void reserve(UUID warehouseId, UUID itemId, BigDecimal qty);

    /** Release a reservation; needs {@code reserved >= qty}. */
    @Transactional(propagation = Propagation.MANDATORY)
    void release(UUID warehouseId, UUID itemId, BigDecimal qty);
}
