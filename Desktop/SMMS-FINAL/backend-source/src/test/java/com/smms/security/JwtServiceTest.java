package com.smms.security;

import com.smms.entity.User;
import com.smms.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for JWT generation and validation.
 * Uses a fixed test secret injected via ReflectionTestUtils
 * (the production secret comes from app.jwt.secret).
 */
class JwtServiceTest {

    // 512-bit base64 secret — long enough for HS algorithms
    private static final String TEST_SECRET = Base64.getEncoder().encodeToString(
        "smms-test-secret-key-for-unit-tests-must-be-long-enough-0123456789".getBytes());

    private JwtService jwtService;
    private User user;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtService, "expiryHours", 8L);
        ReflectionTestUtils.setField(jwtService, "refreshExpiryDays", 7L);

        user = new User();
        user.setUsername("tech1");
        user.setRole(UserRole.technician);
    }

    @Test
    void generatedTokenContainsUsername() {
        String token = jwtService.generateToken(user);
        assertThat(jwtService.extractUsername(token)).isEqualTo("tech1");
    }

    @Test
    void generatedTokenIsValidForItsUser() {
        String token = jwtService.generateToken(user);
        assertThat(jwtService.isTokenValid(token, user)).isTrue();
    }

    @Test
    void tokenIsInvalidForDifferentUser() {
        String token = jwtService.generateToken(user);
        User other = new User();
        other.setUsername("someone-else");
        other.setRole(UserRole.technician);
        assertThat(jwtService.isTokenValid(token, other)).isFalse();
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtService.generateToken(user);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";
        assertThat(jwtService.isTokenValid(tampered, user)).isFalse();
    }

    @Test
    void tokenSignedWithDifferentKeyIsRejected() {
        JwtService rogue = new JwtService();
        ReflectionTestUtils.setField(rogue, "secret", Base64.getEncoder().encodeToString(
            "another-secret-entirely-which-is-also-long-enough-9876543210!!".getBytes()));
        ReflectionTestUtils.setField(rogue, "expiryHours", 8L);
        ReflectionTestUtils.setField(rogue, "refreshExpiryDays", 7L);

        String forged = rogue.generateToken(user);
        assertThat(jwtService.isTokenValid(forged, user)).isFalse();
    }

    @Test
    void expiredTokenIsRejected() {
        ReflectionTestUtils.setField(jwtService, "expiryHours", -1L); // already expired
        String token = jwtService.generateToken(user);
        assertThat(jwtService.isTokenValid(token, user)).isFalse();
    }

    @Test
    @DisplayName("tenantId claim round-trips for tenant users")
    void extractsTenantIdClaim() {
        UUID tenantId = UUID.randomUUID();
        user.setTenantId(tenantId);
        String token = jwtService.generateToken(user);
        assertThat(jwtService.extractTenantId(token)).isEqualTo(tenantId);
    }

    @Test
    @DisplayName("null tenantId (platform admin) yields empty claim → null")
    void platformAdminHasNullTenantId() {
        user.setTenantId(null);
        String token = jwtService.generateToken(user);
        assertThat(jwtService.extractTenantId(token)).isNull();
    }

    @Test
    void refreshTokenIsValidAndCarriesUsername() {
        String refresh = jwtService.generateRefreshToken(user);
        assertThat(jwtService.extractUsername(refresh)).isEqualTo("tech1");
        assertThat(jwtService.isTokenValid(refresh, user)).isTrue();
    }

    @Test
    @DisplayName("refresh token carries no tenantId claim → extract returns null")
    void refreshTokenHasNoTenantClaim() {
        user.setTenantId(UUID.randomUUID());
        String refresh = jwtService.generateRefreshToken(user);
        assertThat(jwtService.extractTenantId(refresh)).isNull();
    }

    @Test
    @DisplayName("access token is an access token and not a refresh token")
    void accessTokenTyping() {
        String access = jwtService.generateToken(user);
        assertThat(jwtService.isAccessToken(access)).isTrue();
        assertThat(jwtService.isRefreshToken(access)).isFalse();
    }

    @Test
    @DisplayName("refresh token is rejected as an access token (SEC-003)")
    void refreshTokenIsNotAccessToken() {
        String refresh = jwtService.generateRefreshToken(user);
        assertThat(jwtService.isAccessToken(refresh)).isFalse();
        assertThat(jwtService.isRefreshToken(refresh)).isTrue();
    }

    @Test
    @DisplayName("legacy refresh token (no typ, no role claim) is not an access token")
    void legacyRefreshTokenWithoutRoleIsNotAccessToken() {
        String legacy = io.jsonwebtoken.Jwts.builder()
            .subject("tech1")
            .issuedAt(new java.util.Date())
            .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
            .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                io.jsonwebtoken.io.Decoders.BASE64.decode(TEST_SECRET)))
            .compact();
        assertThat(jwtService.isAccessToken(legacy)).isFalse();
        assertThat(jwtService.isRefreshToken(legacy)).isTrue();
    }

    // ── Secret guard (SEC-011). Secrets are generated at runtime, never literal values. ──

    private static String randomSecret(int bytes) {
        byte[] b = new byte[bytes];
        new java.security.SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    private JwtService withSecret(String secret, String... profiles) {
        JwtService svc = new JwtService();
        ReflectionTestUtils.setField(svc, "secret", secret);
        ReflectionTestUtils.setField(svc, "environment",
            new org.springframework.mock.env.MockEnvironment() {{ setActiveProfiles(profiles); }});
        return svc;
    }

    @Test
    void strongRandomSecretIsAcceptedInProd() {
        withSecret(randomSecret(48), "prod").validateSecret();
    }

    @Test
    void shortSecretIsRefusedInProdAndOnlyWarnedInDev() {
        String shortSecret = randomSecret(16);
        assertThatThrownBy(() -> withSecret(shortSecret, "prod").validateSecret())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_SECRET").hasMessageNotContaining(shortSecret);
        withSecret(shortSecret, "dev").validateSecret();
    }

    @Test
    void invalidBase64IsRefusedUnderEveryProfile() {
        assertThatThrownBy(() -> withSecret("not base64 !!", "prod").validateSecret())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Base64");
        assertThatThrownBy(() -> withSecret("not base64 !!", "dev").validateSecret())
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> withSecret(null, "prod").validateSecret())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void placeholderTextIsRefusedAndItsHashIsListed() {
        String placeholder = "__GENERATED_BY_SETUP__";
        assertThat(JwtService.KNOWN_WEAK_SHA256).contains(JwtService.sha256Hex(placeholder));
        assertThatThrownBy(() -> withSecret(placeholder, "prod").validateSecret())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void knownFingerprintIsRefusedInProdWithoutEchoingTheValue() {
        // A generated value stands in for a leaked one: its hash is registered as known-weak
        String known = randomSecret(64);
        JwtService svc = withSecret(known, "prod");
        ReflectionTestUtils.setField(svc, "knownWeakHashes", java.util.Set.of(JwtService.sha256Hex(known)));
        assertThatThrownBy(svc::validateSecret)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_SECRET").hasMessageNotContaining(known);
        // dev only warns
        JwtService dev = withSecret(known, "dev");
        ReflectionTestUtils.setField(dev, "knownWeakHashes", java.util.Set.of(JwtService.sha256Hex(known)));
        dev.validateSecret();
    }

    @Test
    void committedFingerprintsAreWellFormedHashes() {
        assertThat(JwtService.KNOWN_WEAK_SHA256).hasSize(4).allMatch(h -> h.matches("[0-9a-f]{64}"));
    }

    @Test
    void sha256HexMatchesTheReferenceVector() {
        assertThat(JwtService.sha256Hex("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
