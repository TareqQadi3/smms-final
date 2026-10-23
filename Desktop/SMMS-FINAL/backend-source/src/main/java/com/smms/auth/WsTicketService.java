package com.smms.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Single-use, 30 second WebSocket tickets. A browser cannot set an Authorization header on a
 * WebSocket, and a token in the URL ends up in access logs, so the client asks for a ticket over
 * the authenticated API and offers it as a sub-protocol value. Only the sha-256 of a ticket is kept.
 * The store is in memory: it is correct for one backend replica (Redis is the scale-out path).
 */
@Service
public class WsTicketService {

    public static final int TTL_SECONDS = 30;
    public static final int MAX_TICKETS = 10_000;
    /** 32 random bytes as unpadded base64url. */
    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    public record Ticket(UUID userId, UUID sid, Instant accessExpiresAt, Instant issuedAt, Instant expiresAt) {}

    public static class CapacityExceededException extends RuntimeException {
        public CapacityExceededException() { super("too many pending WebSocket tickets"); }
    }

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Ticket> store = new ConcurrentHashMap<>();
    private final Supplier<Instant> clock;

    @Autowired
    public WsTicketService() { this(Instant::now); }

    public WsTicketService(Supplier<Instant> clock) { this.clock = clock; }

    /** Issues a ticket bound to the user and their login session; the plain value is returned once. */
    public String issue(UUID userId, UUID sid, Instant accessExpiresAt) {
        Instant now = clock.get();
        purgeExpired();
        if (store.size() >= MAX_TICKETS) throw new CapacityExceededException();
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        store.put(hash(ticket), new Ticket(userId, sid, accessExpiresAt, now, now.plus(Duration.ofSeconds(TTL_SECONDS))));
        return ticket;
    }

    /** Removes and returns the ticket when it exists and has not expired; a second call is empty. */
    public Optional<Ticket> consume(String ticket) {
        if (ticket == null || !FORMAT.matcher(ticket).matches()) return Optional.empty();
        Ticket t = store.remove(hash(ticket));
        if (t == null || !t.expiresAt().isAfter(clock.get())) return Optional.empty();
        return Optional.of(t);
    }

    public void purgeExpired() {
        Instant now = clock.get();
        store.values().removeIf(t -> !t.expiresAt().isAfter(now));
    }

    int size() { return store.size(); }

    private static String hash(String ticket) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(ticket.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
