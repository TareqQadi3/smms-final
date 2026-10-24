package com.smms.notify;

import java.util.List;

/** Non-secret description of a channel's provider (shown to system admins). */
public record ProviderStatus(DeliveryChannel channel, String provider, boolean enabled, boolean configured,
                             List<String> problems) {
    public ProviderStatus {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }
}
