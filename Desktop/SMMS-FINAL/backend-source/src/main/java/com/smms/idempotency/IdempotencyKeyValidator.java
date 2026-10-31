package com.smms.idempotency;

import java.util.regex.Pattern;

/** Validates the {@code Idempotency-Key} header: 8..64 characters of letters, digits and '-'. */
public final class IdempotencyKeyValidator {

    public static final String HEADER = "Idempotency-Key";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private IdempotencyKeyValidator() {}

    /** Key of an endpoint that requires one: missing or malformed gives IllegalArgumentException (400). */
    public static String require(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(HEADER + " header is required");
        }
        return validate(key);
    }

    /** Key of an endpoint where it is optional: null/blank gives null, malformed gives 400. */
    public static String optional(String key) {
        if (key == null || key.isBlank()) return null;
        return validate(key);
    }

    private static String validate(String key) {
        if (!VALID.matcher(key).matches()) {
            throw new IllegalArgumentException(
                HEADER + " must be 8-64 characters: letters, digits and '-'");
        }
        return key;
    }
}
