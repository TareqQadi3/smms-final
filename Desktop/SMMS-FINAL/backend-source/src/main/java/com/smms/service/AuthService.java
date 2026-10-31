package com.smms.service;

import com.smms.auth.AuthSessionService;
import com.smms.dto.request.LoginRequest;
import com.smms.entity.AuthSession;
import io.jsonwebtoken.Claims;
import com.smms.dto.response.AuthResponse;
import com.smms.entity.Tenant;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.AuthException;
import com.smms.repository.TenantRepository;
import com.smms.repository.UserRepository;
import com.smms.exception.AccountLockedException;
import com.smms.security.BruteForceProtectionService;
import com.smms.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepo;
    private final TenantRepository tenantRepo;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final BruteForceProtectionService bruteForce;
    private final AuthSessionService sessions;

    /** The password that ships with every fresh install (public in the repository). */
    static final String PUBLIC_DEFAULT_PASSWORD = "Admin@1234";

    @Transactional
    public AuthResponse login(LoginRequest req) {
        // Check the lock BEFORE touching credentials so a locked account cannot be probed.
        if (bruteForce.isBlocked(req.getUsername())) {
            throw new AccountLockedException("Too many failed login attempts — try again later");
        }

        User user = userRepo.findByUsernameAndDeletedAtIsNull(req.getUsername()).orElse(null);
        if (user == null) {
            // unknown usernames are counted too, so existence cannot be inferred from lockout
            bruteForce.recordFailure(req.getUsername());
            throw new AuthException("Invalid credentials");
        }

        if (!user.isActive()) throw new AuthException("Account disabled");
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            bruteForce.recordFailure(req.getUsername());
            throw new AuthException("Invalid credentials");
        }
        bruteForce.recordSuccess(req.getUsername());
        assertTenantAssigned(user);

        user.setLastLogin(Instant.now());
        userRepo.save(user);

        // Computed from the plaintext at login: nothing is re-hashed or written for this flag.
        boolean changeRequired = user.isMustChangePassword()
            || PUBLIC_DEFAULT_PASSWORD.equals(req.getPassword());

        String jti = sessions.newJti();
        Instant expires = Instant.now().plusSeconds(jwtService.getRefreshExpiryDays() * 86400L);
        AuthSession session = sessions.create(user, jti, expires);
        return response(user,
            jwtService.generateToken(user, session.getId()),
            jwtService.generateRefreshToken(user, session.getId(), jti, expires),
            changeRequired);
    }

    /**
     * Rotates the refresh token. The session row is locked, so two concurrent refreshes with the same
     * token are serialised: the second one presents an already-rotated jti and is treated as reuse.
     * The revocation on reuse must commit although the call ends in a 401, hence noRollbackFor.
     */
    @Transactional(noRollbackFor = AuthException.class)
    public AuthResponse refresh(String refreshToken) {
        final String username;
        final UUID sid;
        final String jti;
        final Instant issuedAt;
        try {
            if (!jwtService.isRefreshToken(refreshToken)) throw new AuthException("Invalid token");
            username = jwtService.extractUsername(refreshToken);
            sid = jwtService.extractSessionId(refreshToken);
            jti = jwtService.extractJti(refreshToken);
            issuedAt = jwtService.extractIssuedAt(refreshToken);
        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException e) {
            throw new AuthException("Invalid token");
        }
        // Refresh tokens issued before sessions existed carry no sid/jti: forced re-login.
        if (sid == null || jti == null) throw new AuthException("Invalid token");

        AuthSession session = sessions.lockForRefresh(sid).orElseThrow(() -> new AuthException("Invalid token"));
        User user = userRepo.findByUsernameAndDeletedAtIsNull(username).orElse(null);
        if (user == null || !session.getUserId().equals(user.getId())) {
            throw new AuthException("Invalid token");
        }
        Instant now = Instant.now();
        if (session.isRevoked() || !session.getExpiresAt().isAfter(now)) throw new AuthException("Invalid token");
        if (!user.isEnabled()) {
            log.warn("SECURITY: refresh refused, account is deactivated (userId={})", user.getId());
            sessions.revoke(session, AuthSessionService.REASON_DEACTIVATED);
            throw new AuthException("Invalid token");
        }
        Instant validAfter = user.getTokensValidAfter();
        if (validAfter != null && (issuedAt == null || issuedAt.getEpochSecond() < validAfter.getEpochSecond())) {
            throw new AuthException("Invalid token");
        }
        assertTenantAssigned(user);

        String presented = AuthSessionService.hash(jti);
        if (!presented.equals(session.getCurrentJtiHash())) {
            // The previous jti (or an unknown one) is a replay: someone holds a copy of a rotated token.
            // No grace window: the whole session is revoked and both holders must sign in again.
            log.warn("SECURITY: refresh token reuse detected, session revoked (userId={}, sessionId={})",
                user.getId(), session.getId());
            sessions.revoke(session, AuthSessionService.REASON_REUSE);
            throw new AuthException("Invalid token");
        }

        String newJti = sessions.newJti();
        sessions.rotate(session, newJti);
        return response(user,
            jwtService.generateToken(user, session.getId()),
            jwtService.generateRefreshToken(user, session.getId(), newJti, session.getExpiresAt()),
            user.isMustChangePassword());
    }

    /**
     * Ends the login session of the access token and, when given, of the refresh token. Never throws
     * and never says whether anything was revoked, so it is no oracle. A refresh token is only
     * honoured when it belongs to the same user as the access token.
     */
    @Transactional
    public void logout(String authorizationHeader, String refreshToken) {
        Claims access = null;
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            access = jwtService.parseAllowExpired(authorizationHeader.substring(7))
                .filter(c -> !JwtService.TYPE_REFRESH.equals(c.get("typ"))).orElse(null);
        }
        Claims refresh = refreshToken == null ? null : jwtService.parseAllowExpired(refreshToken)
            .filter(c -> JwtService.TYPE_REFRESH.equals(c.get("typ"))).orElse(null);
        if (access != null) revokeOwned(access);
        if (refresh != null && (access == null || java.util.Objects.equals(access.getSubject(), refresh.getSubject()))) {
            revokeOwned(refresh);
        }
    }

    private void revokeOwned(Claims claims) {
        UUID sid = JwtService.parseUuid(claims.get(JwtService.CLAIM_SID));
        if (sid == null) return;
        sessions.find(sid).ifPresent(s -> userRepo.findById(s.getUserId())
            .filter(u -> u.getUsername().equals(claims.getSubject()))
            .ifPresent(u -> sessions.revoke(s, AuthSessionService.REASON_LOGOUT)));
    }

    private AuthResponse response(User user, String token, String refresh, boolean passwordChangeRequired) {
        Tenant tenant = user.getTenantId() != null
            ? tenantRepo.findById(user.getTenantId()).orElse(null) : null;
        return AuthResponse.builder()
            .token(token)
            .refreshToken(refresh)
            .role(user.getRole())
            .userId(user.getId())
            .username(user.getUsername())
            .fullName(user.getFullName())
            .fullNameAr(user.getFullNameAr())
            .centerId(user.getCenter()   != null ? user.getCenter().getId()   : null)
            .centerName(user.getCenter() != null ? user.getCenter().getName() : null)
            .centerNameAr(user.getCenter() != null ? user.getCenter().getNameAr() : null)
            .tenantId(tenant   != null ? tenant.getId()   : null)
            .tenantName(tenant != null ? tenant.getName() : null)
            .passwordChangeRequired(passwordChangeRequired)
            .build();
    }

    /**
     * Only system_admin is platform-level. Any other account without a tenant would be treated as a
     * platform admin by the tenant filters, so it cannot sign in until a tenant is assigned.
     */
    private void assertTenantAssigned(User user) {
        if (user.getRole() != UserRole.system_admin && user.getTenantId() == null) {
            log.warn("SECURITY: login refused, account has no tenant assigned (userId={})", user.getId());
            throw new AuthException("Invalid credentials");
        }
    }
}
