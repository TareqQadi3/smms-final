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

@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiry-hours:8}")
    private long expiryHours;

    @Value("${app.jwt.refresh-expiry-days:7}")
    private long refreshExpiryDays;

    public String generateToken(User user) {
        return build(user, expiryHours * 3600_000L,
            Map.of("role", user.getRole().name(),
                   "centerId", user.getCenter() != null ? user.getCenter().getId().toString() : ""));
    }

    public String generateRefreshToken(User user) {
        return build(user, refreshExpiryDays * 86400_000L, Map.of());
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
