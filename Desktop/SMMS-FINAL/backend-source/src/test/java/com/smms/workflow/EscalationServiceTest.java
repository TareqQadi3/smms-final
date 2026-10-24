package com.smms.workflow;

import com.smms.entity.*;
import com.smms.enums.TicketPriority;
import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.WorkflowException;
import com.smms.repository.WorkOrderRepository;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EscalationServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private final UUID tenant = UUID.randomUUID();
    private WorkOrderRepository repo;
    private EscalationRepository esc;
    private NotificationService notifications;
    private WorkOrderAccessPolicy policy;
    private WorkOrderHistoryWriter history;
    private EscalationProperties props;
    private EscalationService service;

    @BeforeEach
    void setUp() {
        repo = mock(WorkOrderRepository.class);
        esc = mock(EscalationRepository.class);
        notifications = mock(NotificationService.class);
        policy = mock(WorkOrderAccessPolicy.class);
        history = mock(WorkOrderHistoryWriter.class);
        props = new EscalationProperties();
        service = new EscalationService(repo, esc, notifications, policy, history, props,
            mock(PlatformTransactionManager.class));
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        when(repo.saveAndFlush(any(WorkOrder.class))).thenAnswer(i -> i.getArgument(0));
    }

    private WorkOrder wo(TicketPriority p, long deadlineAgoSeconds) {
        WorkOrder w = new WorkOrder();
        w.setId(UUID.randomUUID());
        w.setTenantId(tenant);
        w.setPriority(p);
        w.setStatus(TicketStatus.IN_PROGRESS);
        w.setSlaDeadline(NOW.minusSeconds(deadlineAgoSeconds));
        when(repo.findById(w.getId())).thenReturn(Optional.of(w));
        return w;
    }

    private static User user(UserRole r) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(r);
        return u;
    }

    @Test
    void breachRecordsLevelOneOnceAndTwoPassesNotifyOnce() {
        WorkOrder w = wo(TicketPriority.critical, 60);
        // the first insert wins, the second pass (or a second replica) hits the unique constraint
        when(esc.insertIgnore(any(), eq(tenant), eq(w.getId()), eq(1), eq("sla_breach"), isNull(), any()))
            .thenReturn(1, 0);
        when(notifications.notifyEscalation(w, 1)).thenReturn(2);

        assertThat(service.breachOne(w.getId(), NOW)).isTrue();
        w.setSlaBreached(false); // a second replica still saw the old state
        assertThat(service.breachOne(w.getId(), NOW)).isTrue();

        verify(notifications, times(1)).notifyEscalation(w, 1);
        verify(esc).setNotified(any(), eq(2));
    }

    @Test
    void pausedOrAlreadyBreachedOrTerminalOrFutureTicketsAreNeverBreached() {
        WorkOrder paused = wo(TicketPriority.high, 60);
        paused.setSlaPausedAt(NOW.minusSeconds(30));
        WorkOrder done = wo(TicketPriority.high, 60);
        done.setStatus(TicketStatus.COMPLETED);
        WorkOrder already = wo(TicketPriority.high, 60);
        already.setSlaBreached(true);
        WorkOrder future = wo(TicketPriority.high, -60);
        for (WorkOrder w : List.of(paused, done, already, future)) {
            assertThat(service.breachOne(w.getId(), NOW)).isFalse();
        }
        verifyNoInteractions(notifications);
        verify(esc, never()).insertIgnore(any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void ticketWithoutTenantIsSkippedNeverGuessed() {
        WorkOrder w = wo(TicketPriority.high, 60);
        w.setTenantId(null);
        assertThat(service.breachOne(w.getId(), NOW)).isFalse();
    }

    @Test
    void lowPriorityIsRecordedButNotNotified() {
        WorkOrder w = wo(TicketPriority.low, 60);
        when(esc.insertIgnore(any(), any(), any(), eq(1), any(), any(), any())).thenReturn(1);
        service.breachOne(w.getId(), NOW);
        verify(notifications, never()).notifyEscalation(any(WorkOrder.class), anyInt());
        verify(esc).setNotified(any(), eq(0));
        assertThat(w.isSlaBreached()).isTrue();
    }

    @Test
    void notifyPrioritiesAreConfigurable() {
        props.setNotifyPriorities(List.of("low"));
        WorkOrder low = wo(TicketPriority.low, 60);
        WorkOrder crit = wo(TicketPriority.critical, 60);
        when(esc.insertIgnore(any(), any(), any(), eq(1), any(), any(), any())).thenReturn(1);
        service.breachOne(low.getId(), NOW);
        service.breachOne(crit.getId(), NOW);
        verify(notifications).notifyEscalation(low, 1);
        verify(notifications, never()).notifyEscalation(crit, 1);
    }

    @Test
    void levelTwoOnlyAfterTheThreshold() {
        // critical: 4 h window, factor 0.5 -> 2 h beyond the deadline
        WorkOrder before = wo(TicketPriority.critical, 2 * 3600 - 1);
        before.setSlaBreached(true);
        WorkOrder after = wo(TicketPriority.critical, 2 * 3600 + 1);
        after.setSlaBreached(true);
        when(esc.insertIgnore(any(), any(), eq(after.getId()), eq(2), eq("sla_overdue"), isNull(), any())).thenReturn(1);
        when(notifications.notifyEscalation(after, 2)).thenReturn(3);

        assertThat(service.overdueOne(before.getId(), NOW)).isFalse();
        assertThat(service.overdueOne(after.getId(), NOW)).isTrue();

        verify(notifications, never()).notifyEscalation(before, 2);
        verify(notifications).notifyEscalation(after, 2);
        verify(esc).setNotified(any(), eq(3));
    }

    @Test
    void levelTwoDuplicateNotifiesNobodyAndPausedIsExcluded() {
        WorkOrder dup = wo(TicketPriority.high, 10 * 3600);
        dup.setSlaBreached(true);
        when(esc.insertIgnore(any(), any(), eq(dup.getId()), eq(2), any(), any(), any())).thenReturn(0);
        assertThat(service.overdueOne(dup.getId(), NOW)).isFalse();
        WorkOrder paused = wo(TicketPriority.high, 10 * 3600);
        paused.setSlaBreached(true);
        paused.setSlaPausedAt(NOW);
        assertThat(service.overdueOne(paused.getId(), NOW)).isFalse();
        verify(notifications, never()).notifyEscalation(any(WorkOrder.class), eq(2));
    }

    @Test
    void disabledPassDoesNothing() {
        props.setEnabled(false);
        service.runScheduledPass();
        verifyNoInteractions(repo, esc, notifications);
    }

    // ── manual ──

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"resident", "department", "technician", "warehouse", "other",
        "operating_company_manager", "region_supervisor"})
    void manualEscalationDeniedForOtherRoles(UserRole role) {
        assertThatThrownBy(() -> service.escalate(UUID.randomUUID(), "reason here", user(role)))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(policy, esc);
    }

    private WorkOrder readable(User actor) {
        WorkOrder w = wo(TicketPriority.medium, 0);
        when(policy.requireReadable(w.getId(), actor)).thenReturn(w);
        return w;
    }

    @Test
    void manualEscalationRaisesTheNextLevelWithHistory() {
        User mm = user(UserRole.maintenance_manager);
        WorkOrder w = readable(mm);
        when(esc.maxLevel(w.getId())).thenReturn(1);
        when(esc.insertIgnore(any(), eq(tenant), eq(w.getId()), eq(2), eq("manual"), eq(mm.getId()), eq("needs attention")))
            .thenReturn(1);
        when(notifications.notifyEscalation(w, 2)).thenReturn(4);

        var r = service.escalate(w.getId(), "needs attention", mm);

        assertThat(r.level()).isEqualTo(2);
        assertThat(r.trigger()).isEqualTo("manual");
        assertThat(r.notifiedCount()).isEqualTo(4);
        verify(history).note(eq(w), eq(mm), contains("level 2"));
    }

    @Test
    void manualEscalationStopsAtLevelThreePausedAndTerminal() {
        User cr = user(UserRole.control_room);
        WorkOrder w = readable(cr);
        when(esc.maxLevel(w.getId())).thenReturn(3);
        assertThatThrownBy(() -> service.escalate(w.getId(), "reason here", cr)).isInstanceOf(WorkflowException.class);

        WorkOrder paused = readable(cr);
        paused.setSlaPausedAt(NOW);
        assertThatThrownBy(() -> service.escalate(paused.getId(), "reason here", cr)).isInstanceOf(WorkflowException.class);

        WorkOrder closed = readable(cr);
        closed.setStatus(TicketStatus.CLOSED);
        assertThatThrownBy(() -> service.escalate(closed.getId(), "reason here", cr)).isInstanceOf(WorkflowException.class);
        verify(esc, never()).insertIgnore(any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void concurrentManualEscalationLosingTheRaceIs409AndNotifiesNobody() {
        User hs = user(UserRole.hospital_supervisor);
        WorkOrder w = readable(hs);
        when(esc.insertIgnore(any(), any(), any(), eq(1), eq("manual"), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.escalate(w.getId(), "reason here", hs)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(notifications, history);
    }

    @Test
    void outOfScopeTicketIsNotFoundBeforeAnythingIsWritten() {
        User cr = user(UserRole.control_room);
        UUID id = UUID.randomUUID();
        when(policy.requireReadable(id, cr)).thenThrow(new com.smms.exception.ResourceNotFoundException("x"));
        assertThatThrownBy(() -> service.escalate(id, "reason here", cr))
            .isInstanceOf(com.smms.exception.ResourceNotFoundException.class);
        verifyNoInteractions(esc);
    }
}
