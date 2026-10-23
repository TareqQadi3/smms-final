package com.smms.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.cache.SubscriptionCache;
import com.smms.config.SecurityConfig;
import com.smms.entity.AuthSession;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.GlobalExceptionHandler;
import com.smms.repository.TenantSubscriptionRepository;
import com.smms.security.JwtAuthFilter;
import com.smms.security.JwtService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real SecurityConfig and JwtAuthFilter in front of probe endpoints: unauthenticated (missing,
 * garbage, expired, revoked, deactivated, deleted) is 401; authenticated but not allowed is 403;
 * allowed is 200. This is the defect behind admin-rule checks #24 and #231 (403 instead of 401).
 */
class SecurityChainTest {

    static final SessionTestSupport SESSIONS = new SessionTestSupport();
    static final JwtService JWT = SessionTestSupport.jwt();
    static final User TECH = SessionTestSupport.user(UserRole.technician);
    static final User ADMIN = SessionTestSupport.user(UserRole.system_admin);
    static boolean techExists = true;

    @RestController
    static class Probe {
        @GetMapping("/api/probe/any") String any() { return "ok"; }
        @GetMapping("/api/probe/admin") @PreAuthorize("hasRole('system_admin')") String admin() { return "ok"; }
        @PostMapping("/api/auth/probe") String open() { return "open"; }
        @GetMapping("/ws/probe") String ws() { return "ws-permit"; }
        @GetMapping("/uploads/x") String uploads() { return "never"; }
    }

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class Cfg {
        @Bean ObjectMapper objectMapper() { return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(); }
        @Bean UserDetailsService userDetailsService() {
            return username -> {
                if ("tech".equals(username) && techExists) return TECH;
                if ("root".equals(username)) return ADMIN;
                throw new UsernameNotFoundException("no");
            };
        }
        @Bean JwtAuthFilter jwtAuthFilter(ObjectMapper om, UserDetailsService uds) {
            return new JwtAuthFilter(JWT, uds, mock(TenantSubscriptionRepository.class),
                mock(SubscriptionCache.class), om, SESSIONS.service);
        }
        @Bean Probe probe() { return new Probe(); }
        @Bean GlobalExceptionHandler advice() { return new GlobalExceptionHandler(); }
    }

    private MockMvc mvc;
    private AuthSession techSession;

    @BeforeEach
    void setUp() {
        techExists = true;
        TECH.setUsername("tech");
        TECH.setActive(true);
        TECH.setDeletedAt(null);
        ADMIN.setUsername("root");
        SESSIONS.store.clear();
        techSession = SESSIONS.service.create(TECH, "j", Instant.now().plusSeconds(3600));
        AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new org.springframework.mock.web.MockServletContext());
        ctx.register(Cfg.class);
        ctx.refresh();
        mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) ctx)
            .addFilters(ctx.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private String techToken() { return "Bearer " + JWT.generateToken(TECH, techSession.getId()); }

    @Test
    void anonymousIs401WithJsonBodyNotSpringsDefault403() throws Exception {
        mvc.perform(get("/api/probe/any")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.timestamp").exists());
        mvc.perform(get("/api/probe/admin")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/platform/tenants")).andExpect(status().isUnauthorized());
    }

    @Test
    void garbageTamperedAndForeignKeyTokensAre401() throws Exception {
        mvc.perform(get("/api/probe/any").header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
        String t = techToken();
        mvc.perform(get("/api/probe/any").header("Authorization", t.substring(0, t.length() - 3) + "abc"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/probe/any").header("Authorization", "Basic abc")).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenAsBearerIs401() throws Exception {
        mvc.perform(get("/api/probe/any").header("Authorization", "Bearer "
            + JWT.generateRefreshToken(TECH, techSession.getId(), "jt", Instant.now().plusSeconds(60))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredAccessTokenIs401() throws Exception {
        String expired = io.jsonwebtoken.Jwts.builder().subject("tech")
            .claim("typ", "access").claim("role", "technician").claim("tenantId", TECH.getTenantId().toString())
            .issuedAt(new java.util.Date(System.currentTimeMillis() - 20_000))
            .expiration(new java.util.Date(System.currentTimeMillis() - 10_000))
            .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                io.jsonwebtoken.io.Decoders.BASE64.decode(SessionTestSupport.TEST_SECRET))).compact();
        mvc.perform(get("/api/probe/any").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenIs200() throws Exception {
        mvc.perform(get("/api/probe/any").header("Authorization", techToken())).andExpect(status().isOk());
    }

    @Test
    void authenticatedButWrongRoleStaysForbidden403() throws Exception {
        mvc.perform(get("/api/probe/admin").header("Authorization", techToken())).andExpect(status().isForbidden());
    }

    @Test
    void adminPassesTheRoleCheck() throws Exception {
        mvc.perform(get("/api/probe/admin").header("Authorization", "Bearer " + JWT.generateToken(ADMIN)))
            .andExpect(status().isOk());
    }

    @Test
    void revokedSessionTokenIs401OnTheNextRequest() throws Exception {
        String t = techToken();
        mvc.perform(get("/api/probe/any").header("Authorization", t)).andExpect(status().isOk());
        SESSIONS.service.revoke(techSession, "logout");
        mvc.perform(get("/api/probe/any").header("Authorization", t)).andExpect(status().isUnauthorized());
    }

    @Test
    void deactivatedUserIs401OnTheNextRequest() throws Exception {
        String t = techToken();
        TECH.setActive(false);
        mvc.perform(get("/api/probe/any").header("Authorization", t)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenOfADeletedOrRenamedAccountIs401NotA500() throws Exception {
        String t = techToken();
        techExists = false;
        mvc.perform(get("/api/probe/any").header("Authorization", t)).andExpect(status().isUnauthorized());
    }

    @Test
    void authEndpointsStayOpenWithoutAToken() throws Exception {
        mvc.perform(post("/api/auth/probe")).andExpect(status().isOk());
    }

    @Test
    void websocketPathIsNotBlockedByTheHttpLayer() throws Exception {
        // the handshake interceptor is the gate (fail-closed, tested separately)
        mvc.perform(get("/ws/probe")).andExpect(status().isOk());
    }

    @Test
    void uploadsRemainDeniedForEveryone() throws Exception {
        mvc.perform(get("/uploads/x").header("Authorization", techToken())).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void filterLevelDenialIsWrittenDirectlyNotViaSendError() throws Exception {
        // sendError would trigger the container's /error dispatch, where the JWT filter does not run
        // and the caller would look anonymous: 401 instead of 403
        var res = mvc.perform(get("/uploads/x").header("Authorization", techToken())).andReturn().getResponse();
        org.assertj.core.api.Assertions.assertThat(res.getErrorMessage()).isNull();
        org.assertj.core.api.Assertions.assertThat(res.getContentType()).startsWith("application/json");
    }

    @Test
    void ticketEndpointAndAttachmentContentRequireAuthentication() throws Exception {
        mvc.perform(post("/api/ws/ticket")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/work-orders/" + java.util.UUID.randomUUID() + "/attachments/" + java.util.UUID.randomUUID()
            + "/content")).andExpect(status().isUnauthorized());
    }
}
