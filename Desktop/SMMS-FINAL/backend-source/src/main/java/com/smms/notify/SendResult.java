package com.smms.notify;

/**
 * Outcome of one send attempt. {@link #accepted} is true ONLY when the provider confirmed it took the
 * message. {@code retryable=false} marks a permanent failure (bad address, rejected credentials).
 */
public record SendResult(boolean accepted, String providerMessageId, String error, boolean retryable) {

    public static SendResult ok(String providerMessageId) {
        return new SendResult(true, providerMessageId, null, false);
    }

    public static SendResult transientFailure(String error) {
        return new SendResult(false, null, error, true);
    }

    public static SendResult permanentFailure(String error) {
        return new SendResult(false, null, error, false);
    }
}
