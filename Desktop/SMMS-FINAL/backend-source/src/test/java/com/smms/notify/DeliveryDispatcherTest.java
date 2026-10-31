package com.smms.notify;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryDispatcherTest {

    private static final Instant T0 = Instant.parse("2026-03-10T10:00:00Z");

    /** Controllable provider. */
    private static final class FakeProvider implements NotificationProvider {
        final DeliveryChannel channel;
        final boolean available;
        Function<OutboundMessage, SendResult> behaviour = m -> SendResult.ok("pm-1");
        final List<OutboundMessage> sent = new ArrayList<>();
        FakeProvider(DeliveryChannel c, boolean available) { this.channel = c; this.available = available; }
        public DeliveryChannel channel() { return channel; }
        public String name() { return available ? "fake" : "none"; }
        public boolean available() { return available; }
        public ProviderStatus status() { return new ProviderStatus(channel, name(), available, available, List.of()); }
        public SendResult send(OutboundMessage m) { sent.add(m); return behaviour.apply(m); }
    }

    private final InMemoryDeliveryStore store = new InMemoryDeliveryStore();
    private final NotificationProperties props = new NotificationProperties();
    private Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

    private DeliveryDispatcher dispatcher(NotificationProvider email) {
        return new DeliveryDispatcher(store, new ProviderRegistry(email, new NotConfiguredProvider(DeliveryChannel.sms)), props, clock);
    }

    @Test
    void noProviderRecordsNotConfiguredAndNeverSent() {
        var row = store.add(DeliveryChannel.email, "u@example.org", T0);
        var smsRow = store.add(DeliveryChannel.sms, "+966501234567", T0);
        int handled = dispatcher(new NotConfiguredProvider(DeliveryChannel.email)).dispatchOnce();
        assertThat(handled).isEqualTo(2);
        assertThat(row.status).isEqualTo(DeliveryStatus.not_configured);
        assertThat(smsRow.status).isEqualTo(DeliveryStatus.not_configured);
        assertThat(row.attempts).isZero();
        // terminal: a second pass does nothing
        assertThat(dispatcher(new NotConfiguredProvider(DeliveryChannel.email)).dispatchOnce()).isZero();
    }

    @Test
    void acceptedMessageIsSentWithProviderIdAndNotRetried() {
        FakeProvider email = new FakeProvider(DeliveryChannel.email, true);
        var row = store.add(DeliveryChannel.email, " u@example.org ", T0);
        dispatcher(email).dispatchOnce();
        assertThat(row.status).isEqualTo(DeliveryStatus.sent);
        assertThat(row.messageId).isEqualTo("pm-1");
        assertThat(row.attempts).isEqualTo(1);
        assertThat(email.sent.get(0).destination()).isEqualTo("u@example.org");
        assertThat(dispatcher(email).dispatchOnce()).isZero();
        assertThat(email.sent).hasSize(1);
    }

    @Test
    void missingAddressIsSkippedWithoutCallingTheProvider() {
        FakeProvider email = new FakeProvider(DeliveryChannel.email, true);
        var row = store.add(DeliveryChannel.email, "  ", T0);
        dispatcher(email).dispatchOnce();
        assertThat(row.status).isEqualTo(DeliveryStatus.skipped_no_address);
        assertThat(email.sent).isEmpty();
    }

    @Test
    void transientFailureBacksOffThenDiesAfterMaxAttempts() {
        FakeProvider email = new FakeProvider(DeliveryChannel.email, true);
        email.behaviour = m -> SendResult.transientFailure("smtp down for bob@example.org");
        var row = store.add(DeliveryChannel.email, "bob@example.org", T0);
        Duration[] expected = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6)};
        for (int attempt = 1; attempt <= 5; attempt++) {
            dispatcher(email).dispatchOnce();
            assertThat(row.status).isEqualTo(DeliveryStatus.failed);
            assertThat(row.attempts).isEqualTo(attempt);
            assertThat(row.nextAttemptAt).isEqualTo(clock.instant().plus(expected[attempt - 1]));
            assertThat(row.error).doesNotContain("bob@example.org");
            // not due yet: nothing happens
            assertThat(dispatcher(email).dispatchOnce()).isZero();
            clock = Clock.fixed(row.nextAttemptAt, ZoneOffset.UTC);
        }
        dispatcher(email).dispatchOnce(); // 6th attempt fails: dead
        assertThat(row.status).isEqualTo(DeliveryStatus.dead);
        assertThat(row.attempts).isEqualTo(6);
        clock = Clock.fixed(clock.instant().plus(Duration.ofDays(2)), ZoneOffset.UTC);
        assertThat(dispatcher(email).dispatchOnce()).isZero();
        assertThat(email.sent).hasSize(6);
    }

    @Test
    void permanentFailureIsDeadImmediately() {
        FakeProvider email = new FakeProvider(DeliveryChannel.email, true);
        email.behaviour = m -> SendResult.permanentFailure("rejected");
        var row = store.add(DeliveryChannel.email, "u@example.org", T0);
        dispatcher(email).dispatchOnce();
        assertThat(row.status).isEqualTo(DeliveryStatus.dead);
        assertThat(row.attempts).isEqualTo(1);
    }

    @Test
    void providerExceptionIsContainedAndRetried() {
        FakeProvider email = new FakeProvider(DeliveryChannel.email, true);
        email.behaviour = m -> { throw new IllegalStateException("boom"); };
        var bad = store.add(DeliveryChannel.email, "u@example.org", T0);
        var other = store.add(DeliveryChannel.email, "v@example.org", T0);
        assertThat(dispatcher(email).dispatchOnce()).isEqualTo(2);
        assertThat(bad.status).isEqualTo(DeliveryStatus.failed);
        assertThat(other.status).isEqualTo(DeliveryStatus.failed);
    }

    @Test
    void batchSizeIsHonouredAndLeaseHidesClaimedRows() {
        props.setBatchSize(2);
        FakeProvider email = new FakeProvider(DeliveryChannel.email, true);
        for (int i = 0; i < 5; i++) store.add(DeliveryChannel.email, "u" + i + "@example.org", T0);
        assertThat(dispatcher(email).dispatchOnce()).isEqualTo(2);
        assertThat(dispatcher(email).dispatchOnce()).isEqualTo(2);
        assertThat(dispatcher(email).dispatchOnce()).isEqualTo(1);
        assertThat(store.rows.values()).allMatch(r -> r.status == DeliveryStatus.sent);
    }
}
