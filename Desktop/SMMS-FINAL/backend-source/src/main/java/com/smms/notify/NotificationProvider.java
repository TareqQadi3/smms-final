package com.smms.notify;

/**
 * One external delivery mechanism for one channel. Implementations must never throw for ordinary
 * delivery problems (return a failed {@link SendResult}) and must never log addresses, message
 * content or credentials.
 */
public interface NotificationProvider {

    DeliveryChannel channel();

    /** Short stable name stored in the delivery log (at most 30 characters). */
    String name();

    /** False for the placeholder provider: the dispatcher then records {@code not_configured}. */
    boolean available();

    ProviderStatus status();

    SendResult send(OutboundMessage message);
}
