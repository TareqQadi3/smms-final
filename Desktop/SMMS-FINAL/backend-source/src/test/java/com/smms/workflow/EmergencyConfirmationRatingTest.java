package com.smms.workflow;

import com.smms.dto.response.WorkOrderResponse;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.RequesterConfirmation;
import com.smms.enums.TicketPriority;
import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.mapper.WorkOrderMapper;
import com.smms.repository.WorkOrderRepository;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Emergency mode, requester confirmation, ratings, work notes. */
class EmergencyConfirmationRatingTest {

    private static final Instant NOW = Instant.parse("2026-05-01T10:00:00Z");
    private final UUID tenant = UUID.randomUUID();
    private WorkOrderRepository repo;
    private WorkOrderAccessPolicy policy;
    private WorkOrderHistoryWriter history;
    private NotificationService notifications;
    private EscalationService escalation;
    private EscalationRepository escRepo;
    private EmergencyService emergency;
    private RequesterConfirmationService confirmation;
    private RatingService rating;
    private RatingRepository ratingRepo;
    private WorkNoteService notes;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repo = mock(WorkOrderRepository.class);
        policy = mock(WorkOrderAccessPolicy.class);
        history = mock(WorkOrderHistoryWriter.class);
        notifications = mock(NotificationService.class);
        escalation = mock(EscalationService.class);
        escRepo = mock(EscalationRepository.class);
        ratingRepo = mock(RatingRepository.class);
        WorkOrderMapper mapper = mock(WorkOrderMapper.class);
        when(mapper.toResponse(any(WorkOrder.class))).thenAnswer(i -> WorkOrderResponse.builder().build());
        when(repo.saveAndFlush(any(WorkOrder.class))).thenAnswer(i -> i.getArgument(0));
        emergency = new EmergencyService(repo, policy, history, notifications, escalation, escRepo, mapper);
        emergency.setClock(clock);
        confirmation = new RequesterConfirmationService(repo, policy, history, notifications, mapper, escRepo);
        confirmation.setClock(clock);
        rating = new RatingService(ratingRepo, policy, history);
        rating.setClock(clock);
        notes = new WorkNoteService(policy, history, mock(com.smms.repository.TicketHistoryRepository.class));
    }

    private static User user(UserRole r) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(r);
        return u;
    }

    private WorkOrder wo(TicketStatus s, User requester, User actor) {
        WorkOrder w = new WorkOrder();
        w.setId(UUID.randomUUID());
        w.setTenantId(tenant);
        w.setStatus(s);
        w.setPriority(TicketPriority.low);
        w.setRequestedBy(requester);
        w.setSlaDeadline(NOW.plus(Duration.ofHours(40)));
        when(policy.requireReadable(w.getId(), actor)).thenReturn(w);
        return w;
    }

    // ── emergency ──

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"resident", "department", "technician", "warehouse", "other",
        "operating_company_manager", "region_supervisor", "electrical_manager"})
    void declareDeniedForNonStaffRoles(UserRole role) {
        assertThatThrownBy(() -> emergency.declare(UUID.randomUUID(), "fire in ward", user(role)))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(policy, repo);
    }

    @Test
    void clearIsNotAllowedForControlRoom() {
        assertThatThrownBy(() -> emergency.clear(UUID.randomUUID(), "all clear", user(UserRole.control_room)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void declareMakesItCriticalAndOnlyTightensTheSla() {
        User cr = user(UserRole.control_room);
        WorkOrder w = wo(TicketStatus.IN_PROGRESS, user(UserRole.department), cr);
        Instant old = w.getSlaDeadline();

        emergency.declare(w.getId(), "fire in ward", cr);

        assertThat(w.isEmergency()).isTrue();
        assertThat(w.getPriority()).isEqualTo(TicketPriority.critical);
        assertThat(w.getEmergencyDeclaredAt()).isEqualTo(NOW);
        assertThat(w.getEmergencyDeclaredBy()).isSameAs(cr);
        assertThat(w.getSlaDeadline()).isEqualTo(NOW.plus(Duration.ofHours(4))).isBefore(old);
        verify(escalation).recordEmergencyLevel(eq(w), eq(cr), eq("fire in ward"));
        verify(notifications).notifyEmergency(w, cr);
        verify(history).note(eq(w), eq(cr), contains("fire in ward"));
    }

    @Test
    void declareNeverExtendsAnEarlierDeadline() {
        User mm = user(UserRole.maintenance_manager);
        WorkOrder w = wo(TicketStatus.ASSIGNED, user(UserRole.department), mm);
        Instant sooner = NOW.plusSeconds(600);
        w.setSlaDeadline(sooner);
        emergency.declare(w.getId(), "gas smell", mm);
        assertThat(w.getSlaDeadline()).isEqualTo(sooner);
    }

    @Test
    void declareRejectsTerminalAndAlreadyEmergency() {
        User hs = user(UserRole.hospital_supervisor);
        for (TicketStatus s : new TicketStatus[]{TicketStatus.COMPLETED, TicketStatus.CLOSED, TicketStatus.CANCELLED}) {
            WorkOrder w = wo(s, user(UserRole.department), hs);
            assertThatThrownBy(() -> emergency.declare(w.getId(), "reason here", hs)).isInstanceOf(WorkflowException.class);
        }
        WorkOrder already = wo(TicketStatus.IN_PROGRESS, user(UserRole.department), hs);
        already.setEmergency(true);
        assertThatThrownBy(() -> emergency.declare(already.getId(), "reason here", hs)).isInstanceOf(WorkflowException.class);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void clearKeepsPriorityAndSlaAndWritesHistory() {
        User mm = user(UserRole.maintenance_manager);
        WorkOrder w = wo(TicketStatus.IN_PROGRESS, user(UserRole.department), mm);
        w.setEmergency(true);
        w.setPriority(TicketPriority.critical);
        Instant deadline = w.getSlaDeadline();

        emergency.clear(w.getId(), "situation resolved", mm);

        assertThat(w.isEmergency()).isFalse();
        assertThat(w.getPriority()).isEqualTo(TicketPriority.critical);
        assertThat(w.getSlaDeadline()).isEqualTo(deadline);
        verify(history).note(eq(w), eq(mm), contains("situation resolved"));
    }

    @Test
    void clearRequiresAnEmergency() {
        User mm = user(UserRole.maintenance_manager);
        WorkOrder w = wo(TicketStatus.IN_PROGRESS, user(UserRole.department), mm);
        assertThatThrownBy(() -> emergency.clear(w.getId(), "situation resolved", mm)).isInstanceOf(WorkflowException.class);
    }

    // ── confirmation ──

    @Test
    void requesterAcceptsACompletedTicketOnce() {
        User req = user(UserRole.resident);
        WorkOrder w = wo(TicketStatus.COMPLETED, req, req);
        confirmation.confirm(w.getId(), true, null, req);
        assertThat(w.getRequesterConfirmation()).isEqualTo(RequesterConfirmation.accepted);
        assertThat(w.getRequesterConfirmedAt()).isEqualTo(NOW);
        verify(notifications).notifyRequesterVerdict(w, req, true);
        verify(history).note(eq(w), eq(req), contains("accepted"));
        assertThatThrownBy(() -> confirmation.confirm(w.getId(), false, "no", req)).isInstanceOf(WorkflowException.class);
    }

    @Test
    void disputeNeedsANote() {
        User req = user(UserRole.department);
        WorkOrder w = wo(TicketStatus.COMPLETED, req, req);
        assertThatThrownBy(() -> confirmation.confirm(w.getId(), false, "  ", req)).isInstanceOf(IllegalArgumentException.class);
        assertThat(w.getRequesterConfirmation()).isNull();
        confirmation.confirm(w.getId(), false, "still leaking", req);
        assertThat(w.getRequesterConfirmation()).isEqualTo(RequesterConfirmation.disputed);
    }

    @Test
    void nonRequesterIs403AndWrongStatusIs422() {
        User req = user(UserRole.resident);
        User other = user(UserRole.control_room);
        WorkOrder w = wo(TicketStatus.COMPLETED, req, other);
        assertThatThrownBy(() -> confirmation.confirm(w.getId(), true, null, other)).isInstanceOf(AccessDeniedException.class);
        WorkOrder open = wo(TicketStatus.IN_PROGRESS, req, req);
        assertThatThrownBy(() -> confirmation.confirm(open.getId(), true, null, req)).isInstanceOf(WorkflowException.class);
        WorkOrder closed = wo(TicketStatus.CLOSED, req, req);
        assertThatThrownBy(() -> confirmation.confirm(closed.getId(), true, null, req)).isInstanceOf(WorkflowException.class);
    }

    @Test
    void confirmationOnAnInvisibleTicketIs404() {
        User req = user(UserRole.resident);
        UUID id = UUID.randomUUID();
        when(policy.requireReadable(id, req)).thenThrow(new ResourceNotFoundException("x"));
        assertThatThrownBy(() -> confirmation.confirm(id, true, null, req)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ── rating ──

    @Test
    void ratingAllowedForClosedOrAcceptedCompletedOnlyByTheRequester() {
        User req = user(UserRole.resident);
        when(ratingRepo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        WorkOrder closed = wo(TicketStatus.CLOSED, req, req);
        closed.setClosedAt(NOW.minus(Duration.ofDays(2)));
        assertThat(rating.rate(closed.getId(), 5, "great", req).score()).isEqualTo(5);

        WorkOrder accepted = wo(TicketStatus.COMPLETED, req, req);
        accepted.setRequesterConfirmation(RequesterConfirmation.accepted);
        accepted.setCompletedDate(NOW.minus(Duration.ofDays(1)));
        assertThat(rating.rate(accepted.getId(), 3, null, req).comment()).isNull();

        WorkOrder disputed = wo(TicketStatus.COMPLETED, req, req);
        disputed.setRequesterConfirmation(RequesterConfirmation.disputed);
        assertThatThrownBy(() -> rating.rate(disputed.getId(), 1, "bad", req)).isInstanceOf(WorkflowException.class);
        WorkOrder unconfirmed = wo(TicketStatus.COMPLETED, req, req);
        assertThatThrownBy(() -> rating.rate(unconfirmed.getId(), 1, "bad", req)).isInstanceOf(WorkflowException.class);
        WorkOrder inProgress = wo(TicketStatus.IN_PROGRESS, req, req);
        assertThatThrownBy(() -> rating.rate(inProgress.getId(), 1, "bad", req)).isInstanceOf(WorkflowException.class);

        User cr = user(UserRole.control_room);
        WorkOrder notMine = wo(TicketStatus.CLOSED, req, cr);
        assertThatThrownBy(() -> rating.rate(notMine.getId(), 5, null, cr)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void ratingWindowIsThirtyDays() {
        User req = user(UserRole.resident);
        when(ratingRepo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        WorkOrder edge = wo(TicketStatus.CLOSED, req, req);
        edge.setClosedAt(NOW.minus(Duration.ofDays(30)));
        assertThat(rating.rate(edge.getId(), 4, null, req)).isNotNull();
        WorkOrder late = wo(TicketStatus.CLOSED, req, req);
        late.setClosedAt(NOW.minus(Duration.ofDays(30)).minusSeconds(1));
        assertThatThrownBy(() -> rating.rate(late.getId(), 4, null, req)).isInstanceOf(WorkflowException.class);
    }

    @Test
    void duplicateRatingIs409EvenWhenTheUniqueConstraintDecides() {
        User req = user(UserRole.resident);
        WorkOrder w = wo(TicketStatus.CLOSED, req, req);
        w.setClosedAt(NOW);
        when(ratingRepo.existsByWorkOrderId(w.getId())).thenReturn(true);
        assertThatThrownBy(() -> rating.rate(w.getId(), 5, null, req)).isInstanceOf(ConflictException.class);

        when(ratingRepo.existsByWorkOrderId(w.getId())).thenReturn(false);
        when(ratingRepo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("work_order_id"));
        assertThatThrownBy(() -> rating.rate(w.getId(), 5, null, req)).isInstanceOf(ConflictException.class);
    }

    @Test
    void scoreOutOfRangeIs400AndMissingRatingIs404() {
        User req = user(UserRole.resident);
        WorkOrder w = wo(TicketStatus.CLOSED, req, req);
        w.setClosedAt(NOW);
        assertThatThrownBy(() -> rating.rate(w.getId(), 6, null, req)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rating.rate(w.getId(), 0, null, req)).isInstanceOf(IllegalArgumentException.class);
        when(ratingRepo.findByWorkOrderId(w.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> rating.get(w.getId(), req)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ── notes ──

    @Test
    void technicianNotesOnlyWhenAssignedAndWorking() {
        User tech = user(UserRole.technician);
        WorkOrder w = wo(TicketStatus.IN_PROGRESS, user(UserRole.department), tech);
        w.setAssignedTo(tech);
        when(history.note(eq(w), eq(tech), any())).thenReturn(new com.smms.entity.TicketHistory());
        notes.add(w.getId(), "replaced the fuse", tech);
        verify(history).note(w, tech, "replaced the fuse");

        w.setStatus(TicketStatus.COMPLETED);
        assertThatThrownBy(() -> notes.add(w.getId(), "late", tech)).isInstanceOf(WorkflowException.class);

        WorkOrder other = wo(TicketStatus.IN_PROGRESS, user(UserRole.department), tech);
        other.setAssignedTo(user(UserRole.technician));
        assertThatThrownBy(() -> notes.add(other.getId(), "x", tech)).isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"resident", "department", "warehouse", "other", "operating_company_manager",
        "electrical_manager", "region_supervisor"})
    void notesDeniedForOtherRoles(UserRole role) {
        assertThatThrownBy(() -> notes.add(UUID.randomUUID(), "x", user(role))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(policy);
    }
}
