package com.smms.procurement;

import java.math.BigDecimal;

/** Quantity rules shared with the inventory block: positive, at most 3 decimals, at most 1,000,000. */
public final class Quantities {

    public static final BigDecimal MAX = new BigDecimal("1000000");

    private Quantities() {}

    /** Validates and returns the value; IllegalArgumentException (400) otherwise. */
    public static BigDecimal require(BigDecimal q, String field) {
        if (q == null) throw new IllegalArgumentException(field + " is required");
        if (q.signum() <= 0) throw new IllegalArgumentException(field + " must be greater than 0");
        if (q.stripTrailingZeros().scale() > 3) throw new IllegalArgumentException(field + " allows at most 3 decimals");
        if (q.compareTo(MAX) > 0) throw new IllegalArgumentException(field + " must be at most 1,000,000");
        return q;
    }

    /** Zero allowed (partial approval). */
    public static BigDecimal requireNonNegative(BigDecimal q, String field) {
        if (q != null && q.signum() == 0) return BigDecimal.ZERO;
        return require(q, field);
    }
}
