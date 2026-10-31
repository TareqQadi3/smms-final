package com.smms.notify;

/**
 * Lifecycle of one delivery row. The names are the database values (CHECK in V51).
 * {@code sent} means the provider accepted the message, never that the user received or read it.
 */
public enum DeliveryStatus {
    pending, failed, sent, dead, not_configured, skipped_no_address;

    /** Rows the dispatcher may pick up. */
    public boolean dispatchable() {
        return this == pending || this == failed;
    }
}
