package com.smms.service;

import com.smms.dto.response.DuplicateCandidateResponse;
import com.smms.dto.response.WorkOrderResponse;
import com.smms.entity.*;
import com.smms.enums.*;
import com.smms.exception.TicketLockedException;
import com.smms.exception.WorkflowException;
import com.smms.mapper.WorkOrderMapper;
import com.smms.repository.TicketHistoryRepository;
import com.smms.repository.UserRepository;
import com.smms.repository.WorkOrderRepository;
import com.smms.security.TenantSecurityService;
import com.smms.security.WorkOrderAccessPolicy;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * M3: return-for-information, duplicate candidates and the "exactly one history row per
 * state-changing operation" audit rule. Mock-based, same style as WorkOrderServiceTest.
 */
class WorkOrderWorkflowM3Test {

    private WorkOrderRepository workOrderRepo;
    private TenantSecurityService tenantSecurity;
    private WorkOrderAccessPolicy accessPolicy;
    private TicketHistoryRepository historyRepo;
    private NotificationService notificationService;
    private WorkOrderService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        workOrderRepo = mock(WorkOrderRepository.class);
        tenantSecurity = mock(TenantSecurityService.class);
        accessPolicy = mock(WorkOrderAccessPolicy.class);
        historyRepo = mock(TicketHistoryRepository.class);
        notificationService = mock(NotificationService.class);
        WorkOrderMapper mapper = mock(WorkOrderMapper.class);
        when(mapper.toResponse(any(WorkOrder.class))).thenAnswer(i -> WorkOrderResponse.builder().build());
        service = new WorkOrderService(workOrderRepo, historyRepo, new WorkflowEngine(), notificationService,
            mapper, mock(PlanEnforcementService.class), tenantSecurity, accessPolicy,
            mock(EntityManager.class), mock(UserRepository.class));
        when(workOrderRepo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
    }

    private static User user(UserRole role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        return u;
    }

    private WorkOrder ticket(TicketStatus status, String stage, User requester, User actor) {
        WorkOrder wo = new WorkOrder();
        wo.setId(UUID.randomUUID());
        wo.setTicketNumber("WO-T-1");
        wo.setStatus(status);
        wo.setCurrentStage(stage);
        wo.setRequestedBy(requester);
        Center c = new Center();
        c.setId(UUID.randomUUID());
        wo.setCenter(c);
        when(accessPolicy.requireReadable(wo.getId(), actor)).thenReturn(wo);
        return wo;
    }

    private TicketHistory oneHistoryRow() {
        ArgumentCaptor<TicketHistory> cap = ArgumentCaptor.forClass(TicketHistory.class);
        verify(historyRepo, times(1)).save(cap.capture());
        return cap.getValue();
    }

    // ── return for information ───────────────────────────

    @Test
    void requestInfoSetsStageWritesOneHistoryRowAndNotifiesRequester() {
        User requester = user(UserRole.department);
        User cro = user(UserRole.control_room);
        WorkOrder wo = ticket(TicketStatus.UNDER_REVIEW, "under_review", requester, cro);

        service.requestInfo(wo.getId(), "which room?", cro);

        assertThat(wo.getStatus()).isEqualTo(TicketStatus.UNDER_REVIEW);
        assertThat(wo.getCurrentStage()).isEqualTo("info_requested");
        TicketHistory h = oneHistoryRow();
        assertThat(h.getFromStatus()).isEqualTo(TicketStatus.UNDER_REVIEW);
        assertThat(h.getToStatus()).isEqualTo(TicketStatus.UNDER_REVIEW);
        assertThat(h.getStage()).isEqualTo("info_requested");
        assertThat(h.getActor()).isSameAs(cro);
        assertThat(h.getActorRole()).isEqualTo(UserRole.control_room);
        assertThat(h.getNote()).contains("which room?");
        verify(notificationService).notifyUser(eq(requester), eq(wo), eq("INFO_REQUESTED"), any());
    }

    @Test
    void requestInfoOnlyWhileUnderReviewAndNotTwice() {
        User cro = user(UserRole.control_room);
        WorkOrder neu = ticket(TicketStatus.NEW, "new", user(UserRole.department), cro);
        assertThatThrownBy(() -> service.requestInfo(neu.getId(), "x", cro)).isInstanceOf(WorkflowException.class);
        WorkOrder pending = ticket(TicketStatus.UNDER_REVIEW, "info_requested", user(UserRole.department), cro);
        assertThatThrownBy(() -> service.requestInfo(pending.getId(), "x", cro)).isInstanceOf(WorkflowException.class);
        verifyNoInteractions(historyRepo);
    }

    @Test
    void requestInfoRespectsTheTicketLock() {
        User cro = user(UserRole.control_room);
        WorkOrder wo = ticket(TicketStatus.UNDER_REVIEW, "under_review", user(UserRole.department), cro);
        wo.setLocked(true);
        wo.setLockedBy(user(UserRole.maintenance_manager));
        assertThatThrownBy(() -> service.requestInfo(wo.getId(), "x", cro)).isInstanceOf(TicketLockedException.class);
        verifyNoInteractions(historyRepo);
    }

    @Test
    void requestInfoUsesTheSameVisibilityPolicy() {
        User cro = user(UserRole.control_room);
        UUID id = UUID.randomUUID();
        when(accessPolicy.requireReadable(id, cro)).thenThrow(new com.smms.exception.ResourceNotFoundException("nf"));
        assertThatThrownBy(() -> service.requestInfo(id, "x", cro))
            .isInstanceOf(com.smms.exception.ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.provideInfo(id, "x", cro))
            .isInstanceOf(com.smms.exception.ResourceNotFoundException.class);
        verifyNoInteractions(historyRepo);
    }

    @Test
    void provideInfoByRequesterClearsStageAndNotifiesControlRoom() {
        User requester = user(UserRole.department);
        WorkOrder wo = ticket(TicketStatus.UNDER_REVIEW, "info_requested", requester, requester);

        service.provideInfo(wo.getId(), "room 12", requester);

        assertThat(wo.getCurrentStage()).isEqualTo("under_review");
        TicketHistory h = oneHistoryRow();
        assertThat(h.getFromStatus()).isEqualTo(TicketStatus.UNDER_REVIEW);
        assertThat(h.getToStatus()).isEqualTo(TicketStatus.UNDER_REVIEW);
        assertThat(h.getActor()).isSameAs(requester);
        assertThat(h.getNote()).contains("room 12");
        verify(notificationService).notifyInfoProvided(wo);
    }

    @Test
    void provideInfoDeniedForNonRequesterAndWhenNothingPending() {
        User requester = user(UserRole.department);
        User other = user(UserRole.control_room);
        WorkOrder wo = ticket(TicketStatus.UNDER_REVIEW, "info_requested", requester, other);
        assertThatThrownBy(() -> service.provideInfo(wo.getId(), "x", other)).isInstanceOf(AccessDeniedException.class);

        WorkOrder idle = ticket(TicketStatus.UNDER_REVIEW, "under_review", requester, requester);
        assertThatThrownBy(() -> service.provideInfo(idle.getId(), "x", requester)).isInstanceOf(WorkflowException.class);
        verifyNoInteractions(historyRepo);
    }

    @Test
    void assignRefusedWhileInfoRequested() {
        User cro = user(UserRole.control_room);
        WorkOrder wo = ticket(TicketStatus.UNDER_REVIEW, "info_requested", user(UserRole.department), cro);
        assertThatThrownBy(() -> service.assign(wo.getId(), UUID.randomUUID(), "n", cro))
            .isInstanceOf(WorkflowException.class).hasMessageContaining("information");
        verify(tenantSecurity, never()).requireUser(any(), any());
        verifyNoInteractions(historyRepo);
    }

    // ── audit: one history row per state-changing operation ──

    @Test
    void assignWritesExactlyOneHistoryRow() {
        User cro = user(UserRole.control_room);
        WorkOrder wo = ticket(TicketStatus.UNDER_REVIEW, "under_review", user(UserRole.department), cro);
        User tech = user(UserRole.technician);
        Center center = wo.getCenter();
        tech.setCenter(center);
        when(tenantSecurity.requireUser(tech.getId(), UserRole.technician)).thenReturn(tech);

        service.assign(wo.getId(), tech.getId(), "go", cro);

        TicketHistory h = oneHistoryRow();
        assertThat(h.getFromStatus()).isEqualTo(TicketStatus.UNDER_REVIEW);
        assertThat(h.getToStatus()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(h.getActor()).isSameAs(cro);
        assertThat(h.getNote()).isEqualTo("go");
        verify(notificationService).notifyUser(eq(tech), eq(wo), eq("ASSIGNED"), any());
    }

    @Test
    void warehouseApprovalCloseReopenEachWriteOneRow() {
        User wh = user(UserRole.warehouse);
        WorkOrder w1 = ticket(TicketStatus.WAITING_MATERIALS, "waiting_materials", user(UserRole.department), wh);
        service.warehouseAction(w1.getId(), false, "none", wh);
        assertThat(w1.getStatus()).isEqualTo(TicketStatus.WAITING_APPROVAL);
        oneHistoryRow();
        verify(notificationService).notifyApprover(w1);

        clearInvocations(historyRepo);
        User opco = user(UserRole.operating_company_manager);
        WorkOrder w2 = ticket(TicketStatus.WAITING_APPROVAL, "waiting_approval", user(UserRole.department), opco);
        service.approvalDecision(w2.getId(), ApprovalDecision.purchase_request, "buy", opco);
        assertThat(w2.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        oneHistoryRow();

        clearInvocations(historyRepo);
        User cro = user(UserRole.control_room);
        WorkOrder w3 = ticket(TicketStatus.COMPLETED, "completed", user(UserRole.department), cro);
        service.close(w3.getId(), "ok", cro);
        assertThat(w3.getClosedAt()).isNotNull();
        oneHistoryRow();

        clearInvocations(historyRepo);
        WorkOrder w4 = ticket(TicketStatus.COMPLETED, "completed", user(UserRole.department), cro);
        service.reopen(w4.getId(), "again", cro);
        assertThat(w4.getReopenedCount()).isEqualTo(1);
        oneHistoryRow();
    }

    // ── duplicate candidates ─────────────────────────────

    private WorkOrder candidate(User requester, String location) {
        WorkOrder c = new WorkOrder();
        c.setId(UUID.randomUUID());
        c.setTicketNumber("WO-" + c.getId().toString().substring(0, 4));
        c.setStatus(TicketStatus.NEW);
        c.setRequestedBy(requester);
        c.setLocationDetail(location);
        c.setCreatedAt(Instant.now());
        return c;
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicatesMatchSameRequesterOrNormalizedLocationOnly() {
        User actor = user(UserRole.control_room);
        User requester = user(UserRole.department);
        WorkOrder base = ticket(TicketStatus.UNDER_REVIEW, "under_review", requester, actor);
        base.setLocationDetail("  Ward   3 ");
        ControlRoom cr = new ControlRoom();
        cr.setId(UUID.randomUUID());
        base.setControlRoom(cr);
        base.setSubType(SubType.electrical);
        WorkOrder sameLoc = candidate(user(UserRole.department), "ward 3");
        WorkOrder sameReq = candidate(requester, "elsewhere");
        WorkOrder neither = candidate(user(UserRole.department), "ward 9");
        WorkOrder blankBoth = candidate(user(UserRole.department), "   ");
        when(workOrderRepo.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(sameLoc, sameReq, neither, blankBoth)));
        when(accessPolicy.readableBy(actor)).thenReturn((r, q, cb) -> cb.conjunction());

        List<DuplicateCandidateResponse> out = service.possibleDuplicates(base.getId(), actor);

        assertThat(out).extracting(DuplicateCandidateResponse::getId)
            .containsExactly(sameLoc.getId(), sameReq.getId());
        // visibility is the caller's, via the shared policy
        verify(accessPolicy).readableBy(actor);
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicatesAreCappedAtFive() {
        User actor = user(UserRole.control_room);
        User requester = user(UserRole.department);
        WorkOrder base = ticket(TicketStatus.UNDER_REVIEW, "under_review", requester, actor);
        ControlRoom cr = new ControlRoom();
        cr.setId(UUID.randomUUID());
        base.setControlRoom(cr);
        List<WorkOrder> many = java.util.stream.IntStream.range(0, 9).mapToObj(i -> candidate(requester, null)).toList();
        when(workOrderRepo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(many));
        when(accessPolicy.readableBy(actor)).thenReturn((r, q, cb) -> cb.conjunction());
        assertThat(service.possibleDuplicates(base.getId(), actor)).hasSize(5);
    }

    @Test
    void duplicatesOfAnInvisibleTicketAre404() {
        User actor = user(UserRole.control_room);
        UUID id = UUID.randomUUID();
        when(accessPolicy.requireReadable(id, actor)).thenThrow(new com.smms.exception.ResourceNotFoundException("nf"));
        assertThatThrownBy(() -> service.possibleDuplicates(id, actor))
            .isInstanceOf(com.smms.exception.ResourceNotFoundException.class);
    }

    @Test
    void normalizeLocation() {
        assertThat(WorkOrderService.normalizeLocation("  Ward \t 3  ")).isEqualTo("ward 3");
        assertThat(WorkOrderService.normalizeLocation(null)).isEmpty();
    }
}
