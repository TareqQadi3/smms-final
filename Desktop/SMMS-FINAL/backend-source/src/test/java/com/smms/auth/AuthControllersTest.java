package com.smms.auth;

import com.smms.controller.AuthController;
import com.smms.dto.response.AuthResponse;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.AuthException;
import com.smms.exception.GlobalExceptionHandler;
import com.smms.security.JwtService;
import com.smms.service.AuthService;
import com.smms.util.CurrentUserArgumentResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllersTest {

    private final AuthService authService = mock(AuthService.class);
    private final JwtService jwt = SessionTestSupport.jwt();
    private final WsTicketService tickets = new WsTicketService();
    private MockMvc mvc;
    private User user;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(authService), new WsTicketController(tickets, jwt))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
            .build();
        user = SessionTestSupport.user(UserRole.technician);
        user.setUsername("tech1");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void logoutIs204WithoutAnyHeader() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
        verify(authService).logout(isNull(), isNull());
    }

    @Test
    void logoutPassesBothHeadersAndStillSwallowsFailures() throws Exception {
        doThrow(new IllegalStateException("db")).when(authService).logout(any(), any());
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer a").header("X-Refresh-Token", "r"))
            .andExpect(status().isNoContent());
        verify(authService).logout(eq("Bearer a"), eq("r"));
    }

    @Test
    void refreshFailureIs401() throws Exception {
        when(authService.refresh("bad")).thenThrow(new AuthException("Invalid token"));
        mvc.perform(post("/api/auth/refresh").header("X-Refresh-Token", "bad")).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshReturnsTheNewBody() throws Exception {
        when(authService.refresh("good")).thenReturn(AuthResponse.builder().token("a").refreshToken("r2")
            .passwordChangeRequired(true).build());
        mvc.perform(post("/api/auth/refresh").header("X-Refresh-Token", "good"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refreshToken").value("r2"))
            .andExpect(jsonPath("$.passwordChangeRequired").value(true));
    }

    @Test
    void ticketEndpointReturnsTicketAndTtlWithoutCaching() throws Exception {
        var s = new SessionTestSupport();
        var session = s.service.create(user, "j", Instant.now().plusSeconds(60));
        String access = jwt.generateToken(user, session.getId());
        String body = mvc.perform(post("/api/ws/ticket").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.expiresInSeconds").value(30))
            .andExpect(jsonPath("$.ticket").isString())
            .andReturn().getResponse().getContentAsString();
        String ticket = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("ticket").asText();
        var consumed = tickets.consume(ticket).orElseThrow();
        assertThat(consumed.userId()).isEqualTo(user.getId());
        assertThat(consumed.sid()).isEqualTo(session.getId());
    }

    @Test
    void ticketEndpointRejectsAGarbageBearer() throws Exception {
        mvc.perform(post("/api/ws/ticket").header("Authorization", "Bearer nope")).andExpect(status().isUnauthorized());
    }
}
