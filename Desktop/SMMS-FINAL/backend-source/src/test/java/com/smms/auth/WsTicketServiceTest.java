package com.smms.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WsTicketServiceTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final WsTicketService tickets = new WsTicketService(now::get);
    private final UUID user = UUID.randomUUID();
    private final UUID sid = UUID.randomUUID();

    @Test
    void ticketIsBoundToUserAndSessionAndIsOpaque() {
        String t = tickets.issue(user, sid, now.get().plusSeconds(3600));
        assertThat(t).matches("^[A-Za-z0-9_-]{43}$");
        var got = tickets.consume(t).orElseThrow();
        assertThat(got.userId()).isEqualTo(user);
        assertThat(got.sid()).isEqualTo(sid);
        assertThat(got.accessExpiresAt()).isEqualTo(now.get().plusSeconds(3600));
    }

    @Test
    void ticketIsSingleUse() {
        String t = tickets.issue(user, sid, now.get().plusSeconds(3600));
        assertThat(tickets.consume(t)).isPresent();
        assertThat(tickets.consume(t)).isEmpty();
    }

    @Test
    void ticketExpiresAfterThirtySeconds() {
        String t = tickets.issue(user, sid, now.get().plusSeconds(3600));
        now.set(now.get().plusSeconds(WsTicketService.TTL_SECONDS - 1));
        String still = tickets.issue(user, sid, now.get().plusSeconds(3600));
        assertThat(tickets.consume(still)).isPresent();
        now.set(now.get().plusSeconds(2));
        assertThat(tickets.consume(t)).isEmpty();
    }

    @Test
    void malformedOrUnknownTicketsAreRejected() {
        assertThat(tickets.consume(null)).isEmpty();
        assertThat(tickets.consume("")).isEmpty();
        assertThat(tickets.consume("short")).isEmpty();
        assertThat(tickets.consume("A".repeat(43))).isEmpty();
        assertThat(tickets.consume("a b".repeat(20))).isEmpty();
        assertThat(tickets.consume("../".repeat(20))).isEmpty();
    }

    @Test
    void twoTicketsAreIndependentAndDifferent() {
        String a = tickets.issue(user, sid, now.get().plusSeconds(60));
        String b = tickets.issue(user, sid, now.get().plusSeconds(60));
        assertThat(a).isNotEqualTo(b);
        assertThat(tickets.consume(a)).isPresent();
        assertThat(tickets.consume(b)).isPresent();
    }

    @Test
    void expiredTicketsArePurgedOnInsertAndStoreIsCapped() {
        tickets.issue(user, sid, now.get().plusSeconds(60));
        now.set(now.get().plusSeconds(120));
        tickets.issue(user, sid, now.get().plusSeconds(60));
        assertThat(tickets.size()).isEqualTo(1);

        for (int i = 1; i < WsTicketService.MAX_TICKETS; i++) tickets.issue(user, sid, now.get().plusSeconds(60));
        assertThatThrownBy(() -> tickets.issue(user, sid, now.get().plusSeconds(60)))
            .isInstanceOf(WsTicketService.CapacityExceededException.class);
    }

    @Test
    void storeKeepsOnlyAHashNeverThePlainTicket() throws Exception {
        String t = tickets.issue(user, sid, now.get().plusSeconds(60));
        var field = WsTicketService.class.getDeclaredField("store");
        field.setAccessible(true);
        var map = (java.util.Map<String, ?>) field.get(tickets);
        assertThat(map.keySet()).doesNotContain(t);
        assertThat(map.keySet().iterator().next().toString()).hasSize(64);
    }
}
