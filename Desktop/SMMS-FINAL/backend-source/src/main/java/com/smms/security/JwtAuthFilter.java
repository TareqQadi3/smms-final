package com.smms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.auth.AuthSessionService;
import com.smms.cache.SubscriptionCache;
import com.smms.entity.TenantSubscription;
import com.smms.entity.User;
import com.smms.enums.PlanType;
import com.smms.enums.TenantStatus;
import com.smms.enums.UserRole;
import com.smms.exception.GlobalExceptionHandler;
import com.smms.filter.RequestTracingFilter;
import com.smms.repository.TenantSubscriptionRepository;
import com.smms.util.TenantContext;
import org.slf4j.MDC;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final TenantSubscriptionRepository subscriptionRepo;
    private final SubscriptionCache subscriptionCache;
    private final ObjectMapper objectMapper;
    private final AuthSessionService authSessions;

    /**
     * Hard mode of the default-password policy: while an account is flagged must_change_password it
     * may only reach the change-password screen's endpoints. OFF by default so that nobody, the
     * existing admin included, can be locked out.
     */
    @Value("${smms.security.enforce-password-change:false}")
    private boolean enforcePasswordChange;

    /** Request attribute carrying the login session id of the authenticated token (null for legacy tokens). */
    public static final String REQ_ATTR_SID = "smms.sid";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest req,
                                    @NonNull HttpServletResponse res,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(req, res); return;
        }
        String token    = header.substring(7);
        String username;
        try {
            username = jwtService.extractUsername(token);
        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException e) {
            // malformed, tampered or expired token: continue unauthenticated (entry point answers 401)
            chain.doFilter(req, res); return;
        }

        try {
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails user;
                try {
                    user = userDetailsService.loadUserByUsername(username);
                } catch (UsernameNotFoundException e) {
                    // deleted or renamed account: unauthenticated, so the entry point answers 401
                    logger.warn("SECURITY: rejected token, account no longer exists");
                    chain.doFilter(req, res); return;
                }
                if (jwtService.isAccessToken(token)
                        && jwtService.isTokenValid(token, (com.smms.entity.User) user)
                        && tenantClaimMatches(token, (User) user)
                        && accountAndSessionValid(token, (User) user)) {
                    var auth = new UsernamePasswordAuthenticationToken(
                        user, null, user.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    req.setAttribute(REQ_ATTR_SID, jwtService.extractSessionId(token));

                    // Populate MDC for structured logging
                    User smmsUser = (User) user;
                    MDC.put(RequestTracingFilter.MDC_USER_ID, smmsUser.getId().toString());

                    if (enforcePasswordChange && smmsUser.isMustChangePassword() && !allowedWhilePasswordChangeRequired(req)) {
                        writeError(res, HttpStatus.FORBIDDEN, "Password change required");
                        return;
                    }

                    UUID tenantId = jwtService.extractTenantId(token);

                    if (tenantId != null) {
                        // Load subscription via cache (one DB hit per 5 min per tenant)
                        TenantSubscription sub = subscriptionCache.get(tenantId).orElse(null);
                        TenantStatus status   = sub != null ? sub.getStatus()   : TenantStatus.ACTIVE;
                        PlanType     plan     = sub != null ? sub.getPlanType() : PlanType.FREE;

                        // Auto-expire subscriptions past end date
                        if (status == TenantStatus.ACTIVE && sub != null
                                && sub.getEndDate() != null
                                && sub.getEndDate().isBefore(Instant.now())) {
                            sub.setStatus(TenantStatus.EXPIRED);
                            subscriptionRepo.save(sub);
                            subscriptionCache.invalidate(tenantId);
                            status = TenantStatus.EXPIRED;
                        }

                        // Enforce lifecycle — block write operations for suspended/expired
                        if (status == TenantStatus.EXPIRED) {
                            writeError(res, HttpStatus.PAYMENT_REQUIRED,
                                "Subscription expired — please renew your plan");
                            return;
                        }
                        if (status == TenantStatus.SUSPENDED) {
                            // Allow GET requests (read-only) for suspended tenants
                            if (!req.getMethod().equalsIgnoreCase("GET")) {
                                writeError(res, HttpStatus.FORBIDDEN,
                                    "Account suspended — contact support");
                                return;
                            }
                        }

                        TenantContext.set(tenantId, status, plan);
                        MDC.put(RequestTracingFilter.MDC_TENANT_ID, tenantId.toString());
                    } else {
                        TenantContext.set(null); // platform admin — no restrictions
                    }
                }
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * A tenant user must carry the tenant they are stored with. A missing tenant (claim or row) used
     * to be read as "platform admin" and widened access, so such tokens are never authenticated.
     * Only system_admin may have an empty tenant, and then both sides must be empty.
     */
    private boolean tenantClaimMatches(String token, User user) {
        UUID claim = jwtService.extractTenantId(token);
        UUID stored = user.getTenantId();
        boolean ok = user.getRole() == UserRole.system_admin
            ? java.util.Objects.equals(claim, stored)
            : claim != null && claim.equals(stored);
        if (!ok) {
            logger.warn("SECURITY: rejected token, tenant claim does not match the account's tenant"
                + " (userId=" + user.getId() + ")");
        }
        return ok;
    }

    /**
     * The account must still be enabled, the token must not predate an admin invalidation
     * (deactivation, password reset) and, when it is bound to a login session, that session must be
     * live. Tokens without a session id are legacy tokens: valid until their own expiry unless
     * tokens_valid_after cut them off. Every refusal is a SECURITY warning with the user id only.
     */
    private boolean accountAndSessionValid(String token, User user) {
        if (!user.isEnabled()) {
            logger.warn("SECURITY: rejected token, account is deactivated (userId=" + user.getId() + ")");
            return false;
        }
        Instant validAfter = user.getTokensValidAfter();
        if (validAfter != null) {
            Instant iat = jwtService.extractIssuedAt(token);
            // JWT iat has second precision, so compare whole seconds
            if (iat == null || iat.getEpochSecond() < validAfter.getEpochSecond()) {
                logger.warn("SECURITY: rejected token issued before the account's invalidation time (userId="
                    + user.getId() + ")");
                return false;
            }
        }
        UUID sid = jwtService.extractSessionId(token);
        if (sid != null && !authSessions.isActive(sid, user.getId())) {
            logger.warn("SECURITY: rejected token, login session is revoked, expired or foreign (userId="
                + user.getId() + ")");
            return false;
        }
        return true;
    }

    private static boolean allowedWhilePasswordChangeRequired(HttpServletRequest req) {
        String path = req.getRequestURI();
        return path.equals("/api/users/me") || path.equals("/api/users/me/change-password")
            || path.equals("/api/auth/logout");
    }

    private void writeError(HttpServletResponse res, HttpStatus status, String message)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType("application/json;charset=UTF-8");
        var body = new GlobalExceptionHandler.ErrorBody(status.value(), message, Instant.now());
        res.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
