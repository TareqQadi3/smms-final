package com.smms.notify;

/** What a provider is asked to send. {@code destination} is the real address, never logged or stored. */
public record OutboundMessage(DeliveryChannel channel, String destination, String subject, String body) {
    @Override public String toString() {
        return "OutboundMessage[" + channel + "]"; // never print address or content
    }
}
