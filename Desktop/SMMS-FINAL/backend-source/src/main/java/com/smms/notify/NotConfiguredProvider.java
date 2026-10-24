package com.smms.notify;

import java.util.List;

/**
 * Default provider of every channel: it cannot send anything and says so. It never claims
 * delivery; the dispatcher records {@code not_configured} for rows it would have handled.
 */
public final class NotConfiguredProvider implements NotificationProvider {

    public static final String NAME = "none";

    private final DeliveryChannel channel;
    private final boolean enabledButInvalid;
    private final List<String> problems;

    public NotConfiguredProvider(DeliveryChannel channel) {
        this(channel, false, List.of());
    }

    /** The channel was switched on but its configuration is unusable: reported, never fatal. */
    public NotConfiguredProvider(DeliveryChannel channel, boolean enabledButInvalid, List<String> problems) {
        this.channel = channel;
        this.enabledButInvalid = enabledButInvalid;
        this.problems = problems == null ? List.of() : List.copyOf(problems);
    }

    @Override public DeliveryChannel channel() { return channel; }
    @Override public String name() { return NAME; }
    @Override public boolean available() { return false; }

    @Override public ProviderStatus status() {
        return new ProviderStatus(channel, NAME, enabledButInvalid, false, problems);
    }

    @Override public SendResult send(OutboundMessage message) {
        return SendResult.permanentFailure("not_configured");
    }
}
