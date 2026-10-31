package com.smms.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import com.smms.exception.GlobalExceptionHandler;
import com.smms.exception.WorkflowException;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.workflow.WorkflowDtos.PublicStatusResponse;
import com.smms.workflow.WorkflowDtos.StatusLinkResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublicStatusTest {

    private static final Instant NOW = Instant.parse("2026-06-01T09:00:00Z");
    private StatusLinkRepository links;
    private WorkOrderAccessPolicy policy;
    private WorkOrderHistoryWriter history;
    private PublicStatusProperties props;
    private StatusLinkService service;
    private PublicStatusRateLimiter limiter;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        links = mock(StatusLinkRepository.class);
        policy = mock(WorkOrderAccessPolicy.class);
        history = mock(WorkOrderHistoryWriter.class);
        props = new PublicStatusProperties();
        props.setEnabled(true);
        service = new StatusLinkService(links, policy, history, props);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        limiter = new PublicStatusRateLimiter(props);
        limiter.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new PublicStatusController(service, limiter, props))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(links.saveAndFlush(any(StatusLink.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static User user(UserRole r) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(r);
        return u;
    }

    private WorkOrder wo(User requester, User actor) {
        WorkOrder w = new WorkOrder();
        w.setId(UUID.randomUUID());
        w.setTenantId(UUID.randomUUID());
        w.setTicketNumber("WO-2026-HOSPITAL-00001");
        w.setStatus(TicketStatus.IN_PROGRESS);
        w.setRequestedBy(requester);
        w.setTitle("Secret ward 7 oncology leak");
        w.setDescription("private text");
        w.setLocationDetail("Ward 7");
        when(policy.requireReadable(w.getId(), actor)).thenReturn(w);
        return w;
    }

    @Test
    void responseDtoIsAnExactWhitelist() {
        assertThat(Arrays.stream(PublicStatusResponse.class.getRecordComponents()).map(RecordComponent::getName))
            .containsExactlyInAnyOrder("ticketNumber", "status", "statusGroup", "createdAt", "lastUpdatedAt", "completed");
    }

    @Test
    void publicLookupNeverLeaksPrivateFields() throws Exception {
        WorkOrder w = wo(user(UserRole.resident), user(UserRole.resident));
        w.setAssignedTo(user(UserRole.technician));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(StatusLinkService.toPublic(w));
        assertThat(json).doesNotContain("Secret", "private", "Ward", "assignedTo", "center", "tenant", "title",
            "description", "requestedBy", "note");
        assertThat(json).contains("WO-2026-HOSPITAL-00001");
    }

    @Test
    void statusGroupsMapAllStatuses() {
        for (TicketStatus s : TicketStatus.values()) {
            WorkOrder w = new WorkOrder();
            w.setStatus(s);
            w.setTicketNumber("T");
            PublicStatusResponse r = StatusLinkService.toPublic(w);
            assertThat(List.of("received", "in_progress", "waiting", "done", "cancelled")).contains(r.statusGroup());
            assertThat(r.completed()).isEqualTo(s == TicketStatus.COMPLETED || s == TicketStatus.CLOSED);
        }
        WorkOrder waitingForRequester = new WorkOrder();
        waitingForRequester.setStatus(TicketStatus.UNDER_REVIEW);
        waitingForRequester.setCurrentStage("info_requested");
        assertThat(StatusLinkService.toPublic(waitingForRequester).statusGroup()).isEqualTo("waiting");
    }

    @Test
    void createReturnsTheTokenOnceAndStoresOnlyItsHash() {
        User req = user(UserRole.resident);
        WorkOrder w = wo(req, req);
        StatusLinkResponse r = service.create(w.getId(), req);

        assertThat(r.token()).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(r.path()).isEqualTo("/status/" + r.token());
        assertThat(r.expiresAt()).isEqualTo(NOW.plusSeconds(30L * 86400));
        var cap = org.mockito.ArgumentCaptor.forClass(StatusLink.class);
        verify(links).saveAndFlush(cap.capture());
        assertThat(cap.getValue().getTokenHash()).isEqualTo(StatusLinkService.hash(r.token())).hasSize(64)
            .isNotEqualTo(r.token());
        verify(links).revokeActive(w.getId(), NOW); // the previous link stops working
        verify(history).note(eq(w), eq(req), any());
        // the audit note never contains the token
        verify(history, never()).note(any(), any(), org.mockito.ArgumentMatchers.contains(r.token()));
    }

    @Test
    void tokensAreUnique() {
        User req = user(UserRole.resident);
        WorkOrder w = wo(req, req);
        assertThat(service.create(w.getId(), req).token()).isNotEqualTo(service.create(w.getId(), req).token());
    }

    @Test
    void onlyTheRequesterMayCreateALink() {
        User req = user(UserRole.resident);
        User cr = user(UserRole.control_room);
        WorkOrder w = wo(req, cr);
        assertThatThrownBy(() -> service.create(w.getId(), cr)).isInstanceOf(AccessDeniedException.class);
        verify(links, never()).saveAndFlush(any());
    }

    @Test
    void creationIsRefusedWhileTheFeatureIsOff() {
        props.setEnabled(false);
        User req = user(UserRole.resident);
        WorkOrder w = wo(req, req);
        assertThatThrownBy(() -> service.create(w.getId(), req)).isInstanceOf(WorkflowException.class);
    }

    @Test
    void featureIsOffByDefault() {
        assertThat(new PublicStatusProperties().isEnabled()).isFalse();
    }

    @Test
    void unknownMalformedExpiredAndDisabledAreIdentical404() throws Exception {
        String valid = "A".repeat(43);
        when(links.findWorkOrderByActiveToken(any(), any())).thenReturn(Optional.empty());
        String unknown = mvc.perform(get("/api/public/request-status/" + valid)).andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString().replaceAll("\"timestamp\":[^}]*", "");
        String malformed = mvc.perform(get("/api/public/request-status/short")).andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString().replaceAll("\"timestamp\":[^}]*", "");
        props.setEnabled(false);
        String off = mvc.perform(get("/api/public/request-status/" + valid)).andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString().replaceAll("\"timestamp\":[^}]*", "");
        assertThat(unknown).isEqualTo(malformed).isEqualTo(off);
        // a malformed token never reaches the database
        verify(links, times(1)).findWorkOrderByActiveToken(any(), any());
    }

    @Test
    void validTokenReturnsOnlyTheWhitelistWithNoStoreHeaders() throws Exception {
        WorkOrder w = wo(user(UserRole.resident), user(UserRole.resident));
        String token = "B".repeat(43);
        when(links.findWorkOrderByActiveToken(eq(StatusLinkService.hash(token)), any())).thenReturn(Optional.of(w));
        mvc.perform(get("/api/public/request-status/" + token))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(jsonPath("$.ticketNumber").value("WO-2026-HOSPITAL-00001"))
            .andExpect(jsonPath("$.statusGroup").value("in_progress"))
            .andExpect(jsonPath("$.title").doesNotExist())
            .andExpect(jsonPath("$.description").doesNotExist());
    }

    @Test
    void rateLimitIs30PerMinutePerClientThen429() throws Exception {
        when(links.findWorkOrderByActiveToken(any(), any())).thenReturn(Optional.empty());
        for (int i = 0; i < 30; i++) {
            mvc.perform(get("/api/public/request-status/" + "C".repeat(43)).header("X-Real-IP", "10.0.0.1"))
                .andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/public/request-status/" + "C".repeat(43)).header("X-Real-IP", "10.0.0.1"))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
        // another client is unaffected
        mvc.perform(get("/api/public/request-status/" + "C".repeat(43)).header("X-Real-IP", "10.0.0.2"))
            .andExpect(status().isNotFound());
    }

    @Test
    void rateLimitWindowResetsAndTableIsBounded() {
        PublicStatusProperties p = new PublicStatusProperties();
        p.setRateLimitPerMinute(2);
        PublicStatusRateLimiter l = new PublicStatusRateLimiter(p);
        l.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(l.tryAcquire("a")).isTrue();
        assertThat(l.tryAcquire("a")).isTrue();
        assertThat(l.tryAcquire("a")).isFalse();
        l.setClock(Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC));
        assertThat(l.tryAcquire("a")).isTrue();
        // flooding with distinct keys cannot grow the table without limit
        PublicStatusRateLimiter flood = new PublicStatusRateLimiter(p);
        flood.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        int accepted = 0;
        for (int i = 0; i < PublicStatusRateLimiter.MAX_KEYS + 50; i++) if (flood.tryAcquire("k" + i)) accepted++;
        assertThat(accepted).isEqualTo(PublicStatusRateLimiter.MAX_KEYS);
    }
}
