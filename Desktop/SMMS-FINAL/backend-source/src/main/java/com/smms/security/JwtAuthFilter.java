package com.smms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
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
                UserDetails user = userDetailsService.loadUserByUsername(username);
                if (jwtService.isAccessToken(token)
                        && jwtService.isTokenValid(token, (com.smms.entity.User) user)
                        && tenantClaimMatches(token, (User) user)) {
                    var auth = new UsernamePasswordAuthenticationToken(
                        user, null, user.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                    SecurityContextHolder.getContext().setAuthentication(auth);

                    // Populate MDC for structured logging
                    User smmsUser = (User) user;
                    MDC.put(RequestTracingFilter.MDC_USER_ID, smmsUser.getId().toString());

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

    private void writeError(HttpServletResponse res, HttpStatus status, String message)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType("application/json;charset=UTF-8");
        var body = new GlobalExceptionHandler.ErrorBody(status.value(), message, Instant.now());
        res.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
