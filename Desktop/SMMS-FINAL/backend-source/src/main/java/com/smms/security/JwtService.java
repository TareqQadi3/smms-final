package com.smms.security;

import com.smms.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Optional;
import java.util.HexFormat;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class JwtService {

    /** Minimum decoded secret length (HS256 needs 256 bits). */
    static final int MIN_SECRET_BYTES = 32;

    /**
     * SHA-256 (hex) of secret values that are public or are placeholders: the value that used to be
     * committed in .env.codespaces, the application-dev.yml literal, the old .env.example value and
     * the setup.sh placeholder text. Only hashes are kept so this class never contains a usable
     * secret. Under the prod profile a secret with one of these hashes is refused.
     */
    static final Set<String> KNOWN_WEAK_SHA256 = Set.of(
        "fb2bfb4bbb10b378aaa5e1452184bf9ee94a1dd7f393ec0254e7f5a1757821d9",
        "d3f7b4a5ab3de13335ad7e4e0a29c3c62d83333a4007b1a59d05c634a8f00180",
        "5549ff03a50c89b9733836415bcc71a20100fe1fa6e54235f6b42c4be13fdd29",
        "4976e0e10a8ab028b4ca8e13372c01af4f91f12779f3b027a99fe689771cf8b7");

    public static final String TYPE_ACCESS  = "access";
    public static final String TYPE_REFRESH = "refresh";
    public static final String CLAIM_SID    = "sid";

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiry-hours:8}")
    private long expiryHours;

    @Value("${app.jwt.refresh-expiry-days:7}")
    private long refreshExpiryDays;

    @Autowired(required = false)
    private Environment environment;

    /** Instance copy so tests can add the hash of a generated value; production uses the constant. */
    private Set<String> knownWeakHashes = KNOWN_WEAK_SHA256;

    /**
     * Fails fast at startup. A secret that is not valid Base64 is refused under every profile.
     * Under prod a short, known-public or placeholder secret is refused too. Messages name the
     * environment variable only, never the value.
     */
    @PostConstruct
    void validateSecret() {
        byte[] decoded;
        try {
            decoded = Decoders.BASE64.decode(secret == null ? "" : secret.trim());
        } catch (RuntimeException e) {
            throw new IllegalStateException("JWT_SECRET is missing or is not valid Base64");
        }
        boolean prod = environment != null && environment.acceptsProfiles(Profiles.of("prod"));
        boolean tooShort = decoded.length < MIN_SECRET_BYTES;
        boolean known = secret != null && knownWeakHashes.contains(sha256Hex(secret.trim()));
        if (prod && (tooShort || known)) {
            throw new IllegalStateException(known
                ? "JWT_SECRET is a known public or placeholder value; run setup.sh or set a new random value"
                : "JWT_SECRET is too weak: it must decode to at least " + MIN_SECRET_BYTES + " bytes");
        }
        if (tooShort || known) {
            log.warn("JWT_SECRET is weak or publicly known; acceptable for local development only");
        }
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Legacy shape: an access token without a session id. Production login uses
     * {@link #generateToken(User, UUID)}; this one stays for tests and for simulating tokens that
     * were issued before sessions existed.
     */
    public String generateToken(User user) {
        return generateToken(user, null);
    }

    /** Access token bound to the login session {@code sid} (checked on every request). */
    public String generateToken(User user, UUID sid) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("typ",      TYPE_ACCESS);
        claims.put("role",     user.getRole().name());
        claims.put("centerId", user.getCenter()   != null ? user.getCenter().getId().toString()   : "");
        claims.put("tenantId", user.getTenantId() != null ? user.getTenantId().toString()          : "");
        if (sid != null) claims.put(CLAIM_SID, sid.toString());
        return build(user, new Date(System.currentTimeMillis() + expiryHours * 3600_000L), null, claims);
    }

    /** Extracts tenantId claim from token — empty string means platform admin (null tenant). */
    public UUID extractTenantId(String token) {
        String raw = (String) claims(token).get("tenantId");
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) { return null; }
    }

    /** Legacy refresh token (no session, no jti). Production code never issues it; it is refused on refresh. */
    public String generateRefreshToken(User user) {
        return build(user, new Date(System.currentTimeMillis() + refreshExpiryDays * 86400_000L), null,
            Map.of("typ", TYPE_REFRESH));
    }

    /**
     * Refresh token of a session. {@code jti} is random and single-use (rotation); the expiry is the
     * absolute end of the session so that rotating never extends a login.
     */
    public String generateRefreshToken(User user, UUID sid, String jti, Instant expiresAt) {
        return build(user, Date.from(expiresAt), jti,
            Map.of("typ", TYPE_REFRESH, CLAIM_SID, sid.toString()));
    }

    public long getRefreshExpiryDays() { return refreshExpiryDays; }

    /** Session id claim, or null for legacy tokens. Throws JwtException for an invalid or expired token. */
    public UUID extractSessionId(String token) {
        return parseUuid(claims(token).get(CLAIM_SID));
    }

    /** Refresh-token id claim. */
    public String extractJti(String token) {
        return claims(token).getId();
    }

    public Instant extractIssuedAt(String token) {
        Date d = claims(token).getIssuedAt();
        return d == null ? null : d.toInstant();
    }

    public Instant extractExpiration(String token) {
        return claims(token).getExpiration().toInstant();
    }

    /**
     * Claims of a token whose signature is valid, also when it has expired. Used only to find the
     * session to revoke on logout; never to authenticate.
     */
    public Optional<Claims> parseAllowExpired(String token) {
        try {
            return Optional.of(claims(token));
        } catch (ExpiredJwtException e) {
            return Optional.of(e.getClaims());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static UUID parseUuid(Object raw) {
        if (raw == null) return null;
        try { return UUID.fromString(raw.toString()); } catch (IllegalArgumentException e) { return null; }
    }

    /**
     * True only for tokens usable as API Bearer credentials: must carry a role claim and
     * must not be a refresh token. Tokens without a role claim (e.g. refresh tokens issued
     * before the "typ" claim existed) are rejected because they would bypass tenant scoping.
     */
    public boolean isAccessToken(String token) {
        try {
            Claims c = claims(token);
            String role = (String) c.get("role");
            return role != null && !role.isBlank()
                && !TYPE_REFRESH.equals(c.get("typ"));
        } catch (JwtException e) { return false; }
    }

    /** True only for refresh tokens (legacy refresh tokens: no typ and no role claim). */
    public boolean isRefreshToken(String token) {
        try {
            Claims c = claims(token);
            Object typ = c.get("typ");
            if (typ != null) return TYPE_REFRESH.equals(typ);
            String role = (String) c.get("role");
            return role == null || role.isBlank();
        } catch (JwtException e) { return false; }
    }

    public String extractUsername(String token) {
        return claims(token).getSubject();
    }

    public boolean isTokenValid(String token, User user) {
        try {
            return extractUsername(token).equals(user.getUsername())
                && !claims(token).getExpiration().before(new Date());
        } catch (JwtException e) { return false; }
    }

    private String build(User user, Date expiration, String jti, Map<String, Object> extra) {
        var b = Jwts.builder()
            .subject(user.getUsername())
            .claims(extra)
            .issuedAt(new Date())
            .expiration(expiration);
        if (jti != null) b.id(jti);
        return b.signWith(key()).compact();
    }

    private Claims claims(String token) {
        return Jwts.parser().verifyWith(key()).build()
            .parseSignedClaims(token).getPayload();
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }
}
