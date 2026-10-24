package com.smms.inventory.service;

import java.math.BigDecimal;

/** Quantity validation shared by every stock-changing path: > 0, at most 3 decimals and the unit's decimals, at most 1,000,000. */
public final class QuantityRules {
    public static final BigDecimal MAX = new BigDecimal("1000000");
    public static final int MAX_DECIMALS = 3;

    private QuantityRules() {}

    /** @param uomDecimals decimals allowed by the unit of measure, or null for none configured (then 3). */
    public static BigDecimal validate(BigDecimal qty, Integer uomDecimals) {
        if (qty == null) throw new IllegalArgumentException("quantity is required");
        if (qty.signum() <= 0) throw new IllegalArgumentException("quantity must be greater than 0");
        if (qty.compareTo(MAX) > 0) throw new IllegalArgumentException("quantity must not exceed 1000000");
        int scale = Math.max(qty.stripTrailingZeros().scale(), 0);
        int allowed = uomDecimals == null ? MAX_DECIMALS : Math.min(MAX_DECIMALS, uomDecimals);
        if (scale > allowed) {
            throw new IllegalArgumentException("quantity allows at most " + allowed + " decimal place(s)");
        }
        return qty.setScale(MAX_DECIMALS);
    }
}
