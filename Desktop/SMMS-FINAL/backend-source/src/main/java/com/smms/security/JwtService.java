package com.smms.security;

import com.smms.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

    public static final String TYPE_ACCESS  = "access";
    public static final String TYPE_REFRESH = "refresh";

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiry-hours:8}")
    private long expiryHours;

    @Value("${app.jwt.refresh-expiry-days:7}")
    private long refreshExpiryDays;

    public String generateToken(User user) {
        return build(user, expiryHours * 3600_000L,
            Map.of("typ",      TYPE_ACCESS,
                   "role",     user.getRole().name(),
                   "centerId", user.getCenter()   != null ? user.getCenter().getId().toString()   : "",
                   "tenantId", user.getTenantId() != null ? user.getTenantId().toString()          : ""));
    }

    /** Extracts tenantId claim from token — empty string means platform admin (null tenant). */
    public UUID extractTenantId(String token) {
        String raw = (String) claims(token).get("tenantId");
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) { return null; }
    }

    public String generateRefreshToken(User user) {
        return build(user, refreshExpiryDays * 86400_000L, Map.of("typ", TYPE_REFRESH));
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

    private String build(User user, long ttl, Map<String, Object> extra) {
        return Jwts.builder()
            .subject(user.getUsername())
            .claims(extra)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + ttl))
            .signWith(key())
            .compact();
    }

    private Claims claims(String token) {
        return Jwts.parser().verifyWith(key()).build()
            .parseSignedClaims(token).getPayload();
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }
}
