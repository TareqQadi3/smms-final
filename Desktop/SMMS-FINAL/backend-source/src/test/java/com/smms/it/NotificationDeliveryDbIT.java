package com.smms.it;

import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.notify.*;
import com.smms.dto.response.NotificationResponse;
import com.smms.support.Fixtures;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Delivery log, enqueue rules, dispatcher persistence, inbox and admin listing on a real PostgreSQL. */
@TestPropertySource(properties = "app.notifications.scheduler-enabled=false")
class NotificationDeliveryDbIT extends AbstractDbIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcDeliveryStore store;
    @Autowired DeliveryEnqueuer enqueuer;
    @Autowired NotificationPreferencesService prefs;
    @Autowired NotificationInboxService inbox;
    @Autowired NotificationProperties props;
    @Autowired ProviderRegistry registry;
    @Autowired DeliveryAdminController admin;


    /** The delivery table belongs to this feature only: start each test from an empty log. */
    @org.junit.jupiter.api.BeforeEach
    void emptyLog() { jdbc.update("DELETE FROM notification_deliveries"); }

    @AfterEach
    void clear() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private static void loginAs(String role) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("it", "n/a",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role))));
    }

    private record Person(UUID id, User actor) {}

    private Person person(DbFixtures fx, DbFixtures.Hierarchy h, String phone) {
        UUID id = fx.user(h, "control_room");
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
        jdbc.update("UPDATE users SET phone = ? WHERE id = ?", phone, id);
        User u = Fixtures.user(UserRole.control_room, null, null, h.tenant());
        u.setId(id);
        u.setEmail(email);
        u.setPhone(phone);
        return new Person(id, u);
    }

    private UUID notification(UUID tenant, UUID user, String type, String createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notifications (id, user_id, type, title, body, tenant_id, created_at) VALUES (?, ?, ?, 'T', 'B', ?, ?::timestamptz)",
            id, user, type, tenant, createdAt);
        return id;
    }

    private List<Map<String, Object>> deliveries(UUID notification) {
        return jdbc.queryForList("SELECT * FROM notification_deliveries WHERE notification_id = ? ORDER BY channel", notification);
    }

    private static NotificationProvider fake(DeliveryChannel ch, List<OutboundMessage> sink) {
        return new NotificationProvider() {
            public DeliveryChannel channel() { return ch; }
            public String name() { return "fake"; }
            public boolean available() { return true; }
            public ProviderStatus status() { return new ProviderStatus(ch, "fake", true, true, List.of()); }
            public SendResult send(OutboundMessage m) { sink.add(m); return SendResult.ok("pm-" + m.destination()); }
        };
    }

    @Test
    void migrationShapeAndConstraints() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN ('idx_notif_delivery_due','idx_notif_pref_tenant')", Long.class)).isEqualTo(2);
        DbFixtures fx = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = fx.hierarchy();
        Person p = person(fx, h, "+966501112222");
        UUID n = notification(h.tenant(), p.id(), "NEW_TICKET", Instant.now().toString());
        jdbc.update("INSERT INTO notification_deliveries (tenant_id, notification_id, user_id, channel, status) VALUES (?, ?, ?, 'email', 'pending')", h.tenant(), n, p.id());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO notification_deliveries (tenant_id, notification_id, user_id, channel, status) VALUES (?, ?, ?, 'email', 'pending')", h.tenant(), n, p.id()))
            .as("one row per notification and channel").isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO notification_deliveries (tenant_id, notification_id, user_id, channel, status) VALUES (?, ?, ?, 'push', 'pending')", h.tenant(), n, p.id()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE notification_deliveries SET status = 'delivered' WHERE notification_id = ?", n))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE notification_deliveries SET status = 'sent' WHERE notification_id = ?", n))
            .as("sent needs sent_at").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // cascade with the notification
        jdbc.update("DELETE FROM notifications WHERE id = ?", n);
        assertThat(deliveries(n)).isEmpty();
    }

    @Test
    void enqueueRespectsOptInTimeTypesAndTenant() {
        DbFixtures fx = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = fx.hierarchy(), other = fx.hierarchy();
        Person optedIn = person(fx, h, "+966501112222"), noPref = person(fx, h, null), noPhone = person(fx, h, null);
        Instant now = Instant.now();
        UUID before = notification(h.tenant(), optedIn.id(), "NEW_TICKET", now.minus(Duration.ofHours(1)).toString());
        NotificationPreferencesService.Preferences saved = prefs.update(optedIn.actor(),
            new NotificationPreferencesService.Update(true, true, List.of("NEW_TICKET", "ESCALATION")));
        assertThat(saved.emailEnabled()).isTrue();
        assertThat(prefs.get(optedIn.actor()).types()).containsExactly("NEW_TICKET", "ESCALATION");
        UUID wanted = notification(h.tenant(), optedIn.id(), "ESCALATION", now.plusSeconds(1).toString());
        UUID filtered = notification(h.tenant(), optedIn.id(), "STATUS_CHANGE", now.plusSeconds(1).toString());
        UUID ignored = notification(h.tenant(), noPref.id(), "NEW_TICKET", now.plusSeconds(1).toString());
        // a notification whose tenant differs from its recipient's tenant is never delivered externally
        UUID crossTenant = notification(other.tenant(), optedIn.id(), "NEW_TICKET", now.plusSeconds(1).toString());

        enqueuer.enqueue();
        assertThat(deliveries(before)).as("created before opt-in").isEmpty();
        assertThat(deliveries(filtered)).as("type filter").isEmpty();
        assertThat(deliveries(ignored)).as("no preference = in-app only").isEmpty();
        assertThat(deliveries(crossTenant)).isEmpty();
        List<Map<String, Object>> rows = deliveries(wanted);
        assertThat(rows).extracting(r -> r.get("channel")).containsExactly("email", "sms");
        assertThat(rows).extracting(r -> r.get("status")).containsOnly("pending");
        String masked = (String) rows.get(0).get("destination_masked");
        assertThat(masked).contains("***").doesNotContain(optedIn.actor().getEmail());
        assertThat((String) rows.get(1).get("destination_masked")).isEqualTo("***222");
        // idempotent
        assertThat(enqueuer.enqueue()).isZero();
        assertThat(deliveries(wanted)).hasSize(2);
        // preferences validation
        assertThatThrownBy(() -> prefs.update(noPhone.actor(), new NotificationPreferencesService.Update(false, true, null)))
            .isInstanceOf(WorkflowException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_preferences WHERE user_id = ?", Long.class, noPhone.id())).isZero();
        // in-app rows are untouched
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND is_read = false", Long.class, optedIn.id())).isEqualTo(4);
        // turning a channel off clears its enabled_at; delivered rows stay
        prefs.update(optedIn.actor(), new NotificationPreferencesService.Update(true, false, null));
        assertThat(jdbc.queryForObject("SELECT sms_enabled_at IS NULL FROM notification_preferences WHERE user_id = ?", Boolean.class, optedIn.id())).isTrue();
    }

    @Test
    void missingAddressIsRecordedAsSkipped() {
        DbFixtures fx = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = fx.hierarchy();
        Person p = person(fx, h, "+966501112222");
        prefs.update(p.actor(), new NotificationPreferencesService.Update(true, true, null));
        jdbc.update("UPDATE users SET phone = NULL WHERE id = ?", p.id()); // phone removed after opting in
        UUID n = notification(h.tenant(), p.id(), "NEW_TICKET", Instant.now().plusSeconds(1).toString());
        enqueuer.enqueue();
        Map<String, String> byChannel = new HashMap<>();
        deliveries(n).forEach(r -> byChannel.put((String) r.get("channel"), (String) r.get("status")));
        assertThat(byChannel).containsEntry("email", "pending").containsEntry("sms", "skipped_no_address");
    }

    @Test
    void dispatcherWithoutProviderRecordsNotConfiguredNeverSent() {
        DbFixtures fx = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = fx.hierarchy();
        Person p = person(fx, h, "+966501112222");
        prefs.update(p.actor(), new NotificationPreferencesService.Update(true, true, null));
        UUID n = notification(h.tenant(), p.id(), "NEW_TICKET", Instant.now().plusSeconds(1).toString());
        enqueuer.enqueue();
        // the real default registry of this context: both channels are off
        assertThat(registry.forChannel(DeliveryChannel.email).available()).isFalse();
        new DeliveryDispatcher(store, registry, props, Clock.fixed(Instant.now().plusSeconds(5), java.time.ZoneOffset.UTC)).dispatchOnce();
        assertThat(deliveries(n)).extracting(r -> r.get("status")).containsOnly("not_configured");
        assertThat(deliveries(n)).extracting(r -> r.get("sent_at")).containsOnly((Object) null);
        assertThat(jdbc.queryForObject("SELECT is_read FROM notifications WHERE id = ?", Boolean.class, n)).isFalse();
    }

    @Test
    void dispatcherSendsRetriesAndDiesWithPersistedState() {
        DbFixtures fx = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = fx.hierarchy();
        Person p = person(fx, h, "+966501112222");
        prefs.update(p.actor(), new NotificationPreferencesService.Update(true, false, null));
        UUID ok = notification(h.tenant(), p.id(), "NEW_TICKET", Instant.now().plusSeconds(1).toString());
        enqueuer.enqueue();
        List<OutboundMessage> sink = new ArrayList<>();
        Clock later = Clock.fixed(Instant.now().plusSeconds(10), java.time.ZoneOffset.UTC);
        ProviderRegistry good = new ProviderRegistry(fake(DeliveryChannel.email, sink), new NotConfiguredProvider(DeliveryChannel.sms));
        new DeliveryDispatcher(store, good, props, later).dispatchOnce();
        Map<String, Object> row = deliveries(ok).get(0);
        assertThat(row.get("status")).isEqualTo("sent");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("provider")).isEqualTo("fake");
        assertThat(row.get("provider_message_id")).isEqualTo("pm-" + p.actor().getEmail());
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(sink).extracting(OutboundMessage::destination).containsOnlyOnce(p.actor().getEmail());

        // failing provider: backoff schedule persisted, then dead
        UUID bad = notification(h.tenant(), p.id(), "NEW_TICKET", Instant.now().plusSeconds(2).toString());
        enqueuer.enqueue();
        NotificationProvider failing = new NotificationProvider() {
            public DeliveryChannel channel() { return DeliveryChannel.email; }
            public String name() { return "failing"; }
            public boolean available() { return true; }
            public ProviderStatus status() { return new ProviderStatus(channel(), name(), true, true, List.of()); }
            public SendResult send(OutboundMessage m) { return SendResult.transientFailure("down " + m.destination()); }
        };
        ProviderRegistry bad1 = new ProviderRegistry(failing, new NotConfiguredProvider(DeliveryChannel.sms));
        Instant t = Instant.now().plusSeconds(10);
        long[] expectedSeconds = {60, 300, 1800, 7200, 21600};
        for (int attempt = 1; attempt <= 5; attempt++) {
            new DeliveryDispatcher(store, bad1, props, Clock.fixed(t, java.time.ZoneOffset.UTC)).dispatchOnce();
            Map<String, Object> r = deliveries(bad).get(0);
            assertThat(r.get("status")).isEqualTo("failed");
            assertThat(r.get("attempts")).isEqualTo(attempt);
            assertThat(((java.sql.Timestamp) r.get("next_attempt_at")).toInstant().getEpochSecond() - t.getEpochSecond()).isEqualTo(expectedSeconds[attempt - 1]);
            assertThat((String) r.get("last_error")).doesNotContain(p.actor().getEmail());
            // not due again before the backoff elapsed
            assertThat(new DeliveryDispatcher(store, bad1, props, Clock.fixed(t.plusSeconds(1), java.time.ZoneOffset.UTC)).dispatchOnce()).isZero();
            t = ((java.sql.Timestamp) r.get("next_attempt_at")).toInstant();
        }
        new DeliveryDispatcher(store, bad1, props, Clock.fixed(t, java.time.ZoneOffset.UTC)).dispatchOnce();
        Map<String, Object> dead = deliveries(bad).get(0);
        assertThat(dead.get("status")).isEqualTo("dead");
        assertThat(dead.get("attempts")).isEqualTo(6);
    }

    @Test
    void concurrentClaimsNeverShareARow() throws Exception {
        DbFixtures fx = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = fx.hierarchy();
        Person p = person(fx, h, null);
        for (int i = 0; i < 30; i++) {
            UUID n = notification(h.tenant(), p.id(), "NEW_TICKET", Instant.now().toString());
            jdbc.update("INSERT INTO notification_deliveries (tenant_id, notification_id, user_id, channel, status, next_attempt_at) VALUES (?, ?, ?, 'email', 'pending', now() - interval '1 minute')",
                h.tenant(), n, p.id());
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<List<DeliveryStore.Claimed>>> futures = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> store.claimDue(now, now.plusSeconds(300), 10)));
        Set<UUID> seen = new HashSet<>();
        int total = 0;
        for (var fu : futures) for (var c : fu.get(30, TimeUnit.SECONDS)) { total++; seen.add(c.id()); }
        pool.shutdown();
        assertThat(seen).as("no row claimed twice").hasSize(total);
        assertThat(total).isGreaterThanOrEqualTo(30); // other tests' rows may also be due; ours are all claimed
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries WHERE user_id = ? AND next_attempt_at > now()", Long.class, p.id())).isEqualTo(30);
    }

    @Test
    void inboxMarkReadIsOwnerOnlyAndListCarriesWorkOrderId() {
        DbFixtures fx = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = fx.hierarchy(), other = fx.hierarchy();
        Person me = person(fx, h, null), stranger = person(fx, other, null);
        UUID wo = fx.workOrder(h, me.id(), "new", "low");
        UUID n = notification(h.tenant(), me.id(), "NEW_TICKET", Instant.now().toString());
        jdbc.update("UPDATE notifications SET work_order_id = ? WHERE id = ?", wo, n);
        UUID plain = notification(h.tenant(), me.id(), "STATUS_CHANGE", Instant.now().minusSeconds(5).toString());
        List<NotificationResponse> page = inbox.list(me.id(), PageRequest.of(0, 10, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))).getContent();
        assertThat(page).extracting(NotificationResponse::getWorkOrderId).containsExactly(wo, null);
        assertThatThrownBy(() -> inbox.markRead(stranger.id(), n)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT is_read FROM notifications WHERE id = ?", Boolean.class, n)).isFalse();
        assertThatThrownBy(() -> inbox.markRead(me.id(), UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        inbox.markRead(me.id(), n);
        inbox.markRead(me.id(), n); // idempotent
        assertThat(jdbc.queryForObject("SELECT is_read FROM notifications WHERE id = ?", Boolean.class, n)).isTrue();
        assertThat(jdbc.queryForObject("SELECT is_read FROM notifications WHERE id = ?", Boolean.class, plain)).isFalse();
    }

    @Test
    void adminListingIsTenantScopedMaskedAndFiltered() {
        DbFixtures fx = new DbFixtures(jdbc);
        DbFixtures.Hierarchy a = fx.hierarchy(), b = fx.hierarchy();
        Person pa = person(fx, a, null), pb = person(fx, b, null);
        for (var pair : List.of(Map.entry(a, pa), Map.entry(b, pb))) {
            UUID n = notification(pair.getKey().tenant(), pair.getValue().id(), "NEW_TICKET", Instant.now().toString());
            jdbc.update("INSERT INTO notification_deliveries (tenant_id, notification_id, user_id, channel, destination_masked, status) VALUES (?, ?, ?, 'email', 'u***@t.invalid', 'dead')",
                pair.getKey().tenant(), n, pair.getValue().id());
        }
        TenantContext.set(a.tenant());
        loginAs("control_room");
        assertThatThrownBy(() -> admin.providers()).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        loginAs("system_admin");
        User sa = Fixtures.user(UserRole.system_admin, null, null, a.tenant());
        var page = admin.deliveries("dead", "email", null, null, 0, 20, sa).getBody();
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().get(0).userId()).isEqualTo(pa.id());
        assertThat(page.content().get(0).destinationMasked()).isEqualTo("u***@t.invalid");
        assertThatThrownBy(() -> admin.deliveries("bogus", null, null, null, 0, 20, sa)).isInstanceOf(IllegalArgumentException.class);
        assertThat(admin.providers().getBody()).hasSize(2).allMatch(s -> !s.configured());
        TenantContext.clear(); // platform admin: all tenants
        assertThat(admin.deliveries("dead", null, null, null, 0, 100, sa).getBody().totalElements()).isGreaterThanOrEqualTo(2);
    }
}
