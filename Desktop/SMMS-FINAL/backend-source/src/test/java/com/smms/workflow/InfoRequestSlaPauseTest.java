package com.smms.workflow;

import com.smms.dto.response.WorkOrderResponse;
import com.smms.entity.*;
import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.WorkflowException;
import com.smms.mapper.WorkOrderMapper;
import com.smms.repository.*;
import com.smms.security.TenantSecurityService;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.service.NotificationService;
import com.smms.service.PlanEnforcementService;
import com.smms.service.WorkOrderService;
import com.smms.service.WorkflowEngine;
import com.smms.dto.request.TransitionRequest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Request for information: SLA pause arithmetic, one open request, cancel during the pause. */
class InfoRequestSlaPauseTest {

    private static final Instant T0 = Instant.parse("2026-01-10T08:00:00Z");
    private final UUID tenant = UUID.randomUUID();
    private WorkOrderRepository workOrderRepo;
    private WorkOrderAccessPolicy accessPolicy;
    private InfoRequestRepository infoRepo;
    private NotificationService notifications;
    private WorkOrderService service;
    private User requester, cro;
    private WorkOrder wo;

    private void at(Instant now) { service.setClock(Clock.fixed(now, ZoneOffset.UTC)); }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        workOrderRepo = mock(WorkOrderRepository.class);
        accessPolicy = mock(WorkOrderAccessPolicy.class);
        infoRepo = mock(InfoRequestRepository.class);
        notifications = mock(NotificationService.class);
        TicketHistoryRepository historyRepo = mock(TicketHistoryRepository.class);
        WorkOrderMapper mapper = mock(WorkOrderMapper.class);
        when(mapper.toResponse(any(WorkOrder.class))).thenAnswer(i -> WorkOrderResponse.builder().build());
        service = new WorkOrderService(workOrderRepo, historyRepo, new WorkflowEngine(), notifications, mapper,
            mock(PlanEnforcementService.class), mock(TenantSecurityService.class), accessPolicy,
            mock(EntityManager.class), mock(UserRepository.class), new WorkOrderHistoryWriter(historyRepo),
            mock(ControlRoomRepository.class), mock(AssetRepository.class), mock(DepartmentRepository.class),
            infoRepo, mock(EscalationRepository.class));
        when(workOrderRepo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        when(infoRepo.saveAndFlush(any(InfoRequest.class))).thenAnswer(i -> i.getArgument(0));
        requester = user(UserRole.department);
        cro = user(UserRole.control_room);
        wo = new WorkOrder();
        wo.setId(UUID.randomUUID());
        wo.setTenantId(tenant);
        wo.setTicketNumber("WO-1");
        wo.setStatus(TicketStatus.UNDER_REVIEW);
        wo.setCurrentStage("under_review");
        wo.setRequestedBy(requester);
        wo.setSlaDeadline(T0.plusSeconds(24 * 3600));
        when(accessPolicy.requireReadable(wo.getId(), cro)).thenReturn(wo);
        when(accessPolicy.requireReadable(wo.getId(), requester)).thenReturn(wo);
    }

    private static User user(UserRole role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        return u;
    }

    @Test
    void requestPausesTheClockAndStoresTheQuestion() {
        at(T0);
        service.requestInfo(wo.getId(), "which room?", cro);

        assertThat(wo.getSlaPausedAt()).isEqualTo(T0);
        assertThat(wo.isSlaPaused()).isTrue();
        assertThat(wo.getCurrentStage()).isEqualTo("info_requested");
        var cap = org.mockito.ArgumentCaptor.forClass(InfoRequest.class);
        verify(infoRepo).saveAndFlush(cap.capture());
        assertThat(cap.getValue().getQuestion()).isEqualTo("which room?");
        assertThat(cap.getValue().getTenantId()).isEqualTo(tenant);
        assertThat(cap.getValue().getRequestedBy()).isSameAs(cro);
    }

    @Test
    void answerShiftsTheDeadlineByExactlyThePausedTimeAndAccountsForIt() {
        Instant deadline = wo.getSlaDeadline();
        at(T0);
        service.requestInfo(wo.getId(), "q", cro);
        InfoRequest open = openRequest();
        at(T0.plusSeconds(5400)); // 90 minutes later
        service.provideInfo(wo.getId(), "the answer", requester);

        assertThat(wo.getSlaDeadline()).isEqualTo(deadline.plusSeconds(5400));
        assertThat(wo.getSlaPausedSeconds()).isEqualTo(5400);
        assertThat(wo.getSlaPausedAt()).isNull();
        assertThat(wo.getCurrentStage()).isEqualTo("under_review");
        assertThat(open.getAnswer()).isEqualTo("the answer");
        assertThat(open.getAnsweredBy()).isSameAs(requester);
        assertThat(open.getPausedSeconds()).isEqualTo(5400L);
    }

    @Test
    void twoPausesAccumulate() {
        Instant deadline = wo.getSlaDeadline();
        at(T0);
        service.requestInfo(wo.getId(), "q1", cro);
        openRequest();
        at(T0.plusSeconds(600));
        service.provideInfo(wo.getId(), "a1", requester);
        when(infoRepo.findOpen(wo.getId())).thenReturn(Optional.empty()); // the first request is closed now
        at(T0.plusSeconds(1000));
        service.requestInfo(wo.getId(), "q2", cro);
        openRequest();
        at(T0.plusSeconds(1000 + 300));
        service.provideInfo(wo.getId(), "a2", requester);
        assertThat(wo.getSlaPausedSeconds()).isEqualTo(900);
        assertThat(wo.getSlaDeadline()).isEqualTo(deadline.plusSeconds(900));
    }

    /** Makes the open row visible to the next findOpen call and returns it. */
    private InfoRequest openRequest() {
        var cap = org.mockito.ArgumentCaptor.forClass(InfoRequest.class);
        verify(infoRepo, atLeastOnce()).saveAndFlush(cap.capture());
        InfoRequest r = cap.getValue();
        when(infoRepo.findOpen(wo.getId())).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void secondRequestWhilePendingIs422() {
        at(T0);
        service.requestInfo(wo.getId(), "q", cro);
        assertThatThrownBy(() -> service.requestInfo(wo.getId(), "again", cro)).isInstanceOf(WorkflowException.class);
        verify(infoRepo, times(1)).saveAndFlush(any());
    }

    @Test
    void concurrentSecondRequestLosingTheUniqueIndexIs409() {
        at(T0);
        when(infoRepo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_info_req_open"));
        assertThatThrownBy(() -> service.requestInfo(wo.getId(), "q", cro)).isInstanceOf(ConflictException.class);
    }

    @Test
    void onlyTheRequesterMayAnswer() {
        at(T0);
        service.requestInfo(wo.getId(), "q", cro);
        assertThatThrownBy(() -> service.provideInfo(wo.getId(), "x", cro))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(wo.isSlaPaused()).isTrue();
    }

    @Test
    void cancelDuringThePauseClosesItTheSameWay() {
        Instant deadline = wo.getSlaDeadline();
        at(T0);
        service.requestInfo(wo.getId(), "q", cro);
        InfoRequest open = openRequest();
        at(T0.plusSeconds(3600));
        TransitionRequest req = new TransitionRequest();
        req.setTargetStatus(TicketStatus.CANCELLED);
        req.setNote("not needed");
        service.transition(wo.getId(), req, cro);

        assertThat(wo.getStatus()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(wo.getSlaPausedAt()).isNull();
        assertThat(wo.getSlaPausedSeconds()).isEqualTo(3600);
        assertThat(wo.getSlaDeadline()).isEqualTo(deadline.plusSeconds(3600));
        assertThat(open.getCancelledAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(open.getAnsweredAt()).isNull();
        assertThat(open.getPausedSeconds()).isEqualTo(3600L);
    }

    @Test
    void legacyPendingRequestWithoutRowOrPauseStillAnswerable() {
        wo.setCurrentStage("info_requested"); // legacy: no pause, no row
        Instant deadline = wo.getSlaDeadline();
        at(T0);
        service.provideInfo(wo.getId(), "a", requester);
        assertThat(wo.getSlaDeadline()).isEqualTo(deadline);
        assertThat(wo.getSlaPausedSeconds()).isZero();
        assertThat(wo.getCurrentStage()).isEqualTo("under_review");
    }

    @Test
    void slaPauseMathNeverMovesBackwards() {
        WorkOrder w = new WorkOrder();
        w.setSlaDeadline(T0);
        SlaPause.pause(w, T0.plusSeconds(100));
        assertThat(SlaPause.resume(w, T0)).isZero(); // clock skew: negative delta is ignored
        assertThat(w.getSlaDeadline()).isEqualTo(T0);
        assertThat(SlaPause.resume(w, T0)).isZero(); // nothing paused
        assertThatThrownBy(() -> { SlaPause.pause(w, T0); SlaPause.pause(w, T0); })
            .isInstanceOf(IllegalStateException.class);
    }
}
