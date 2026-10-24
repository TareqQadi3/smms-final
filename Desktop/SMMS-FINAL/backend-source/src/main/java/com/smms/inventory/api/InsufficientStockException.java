package com.smms.inventory.api;

import com.smms.exception.ConflictException;

import java.math.BigDecimal;

/** Not enough available stock (on hand minus reserved): answered with 409, names the item and the available quantity. */
public class InsufficientStockException extends ConflictException {
    public InsufficientStockException(String sku, BigDecimal available, BigDecimal requested) {
        super("Insufficient stock for item " + sku + ": available " + available.stripTrailingZeros().toPlainString()
            + ", requested " + requested.stripTrailingZeros().toPlainString());
    }
}
