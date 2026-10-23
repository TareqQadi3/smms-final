package com.smms.authz;

import com.smms.dto.request.TransitionRequest;
import com.smms.dto.response.WorkOrderResponse;
import com.smms.entity.Center;
import com.smms.entity.TicketHistory;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import com.smms.exception.TicketLockedException;
import com.smms.exception.WorkflowException;
import com.smms.mapper.WorkOrderMapper;
import com.smms.repository.TicketHistoryRepository;
import com.smms.repository.UserRepository;
import com.smms.repository.WorkOrderRepository;
import com.smms.security.TenantSecurityService;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.service.NotificationService;
import com.smms.service.PlanEnforcementService;
import com.smms.service.WorkOrderService;
import com.smms.service.WorkflowEngine;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * P4 workflow re-validation: the REAL WorkflowEngine behind WorkOrderService.transition, checked
 * against an explicit, independently written table of what /transition may do. Every (from, to,
 * role) combination is exercised, so a rule added to or removed from the engine fails here until
 * the table is updated on purpose.
 */
class WorkOrderTransitionMatrixTest {

    private WorkOrderRepository workOrderRepo;
    private TicketHistoryRepository historyRepo;
    private NotificationService notifications;
    private WorkOrderAccessPolicy accessPolicy;
    private WorkOrderService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        workOrderRepo = mock(WorkOrderRepository.class);
        historyRepo = mock(TicketHistoryRepository.class);
        notifications = mock(NotificationService.class);
        accessPolicy = mock(WorkOrderAccessPolicy.class);
        WorkOrderMapper mapper = mock(WorkOrderMapper.class);
        when(mapper.toResponse(any(WorkOrder.class))).thenAnswer(i -> WorkOrderResponse.builder().build());
        service = new WorkOrderService(workOrderRepo, historyRepo, new WorkflowEngine(), notifications,
            mapper, mock(PlanEnforcementService.class), mock(TenantSecurityService.class), accessPolicy,
            mock(EntityManager.class), mock(UserRepository.class));
        when(workOrderRepo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
    }

    // ── expected behaviour of POST /{id}/transition (independent of the engine's own map) ──

    /** from -> to -> roles that may do it through the generic endpoint (system_admin is added below). */
    private static final Map<TicketStatus, Map<TicketStatus, Set<UserRole>>> VIA_TRANSITION = new EnumMap<>(TicketStatus.class);
    /** Moves that exist in the workflow but must go through their dedicated endpoint (422 on /transition). */
    private static final Set<String> DEDICATED = Set.of(
        "UNDER_REVIEW>ASSIGNED", "COMPLETED>IN_PROGRESS");

    private static void rule(TicketStatus from, TicketStatus to, UserRole... roles) {
        VIA_TRANSITION.computeIfAbsent(from, k -> new EnumMap<>(TicketStatus.class))
            .put(to, roles.length == 0 ? EnumSet.noneOf(UserRole.class) : EnumSet.copyOf(List.of(roles)));
    }

    static {
        rule(TicketStatus.NEW, TicketStatus.UNDER_REVIEW, UserRole.control_room);
        rule(TicketStatus.NEW, TicketStatus.CANCELLED,
            UserRole.control_room, UserRole.maintenance_manager, UserRole.hospital_supervisor);
        rule(TicketStatus.UNDER_REVIEW, TicketStatus.CANCELLED, UserRole.control_room, UserRole.maintenance_manager);
        rule(TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, UserRole.technician);
        rule(TicketStatus.ASSIGNED, TicketStatus.CANCELLED, UserRole.control_room, UserRole.maintenance_manager);
        rule(TicketStatus.IN_PROGRESS, TicketStatus.COMPLETED, UserRole.technician);
        rule(TicketStatus.IN_PROGRESS, TicketStatus.WAITING_MATERIALS, UserRole.technician);
        rule(TicketStatus.IN_PROGRESS, TicketStatus.CANCELLED, UserRole.control_room, UserRole.maintenance_manager);
        rule(TicketStatus.WAITING_MATERIALS, TicketStatus.WAITING_APPROVAL, UserRole.warehouse);
        rule(TicketStatus.WAITING_MATERIALS, TicketStatus.IN_PROGRESS, UserRole.warehouse);
        // approval decisions go through /approval for everyone except the system admin
        rule(TicketStatus.WAITING_APPROVAL, TicketStatus.IN_PROGRESS);
        rule(TicketStatus.WAITING_APPROVAL, TicketStatus.WAITING_MATERIALS);
        rule(TicketStatus.COMPLETED, TicketStatus.CLOSED, UserRole.control_room, UserRole.maintenance_manager);
    }

    /** Is this move a structurally valid workflow edge (any role)? */
    private static boolean edgeExists(TicketStatus from, TicketStatus to) {
        return (VIA_TRANSITION.containsKey(from) && VIA_TRANSITION.get(from).containsKey(to))
            || DEDICATED.contains(from.name() + ">" + to.name());
    }

    private static boolean expectedAllowed(TicketStatus from, TicketStatus to, UserRole role) {
        if (to == TicketStatus.ASSIGNED) return false;                            // /assign only
        if (from == TicketStatus.COMPLETED && to == TicketStatus.IN_PROGRESS) return false; // /reopen only
        if (role == UserRole.system_admin) return edgeExists(from, to);
        return VIA_TRANSITION.getOrDefault(from, Map.of()).getOrDefault(to, Set.of()).contains(role);
    }

    private static User user(UserRole role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        return u;
    }

    private WorkOrder ticket(TicketStatus status, User actor) {
        WorkOrder wo = new WorkOrder();
        wo.setId(UUID.randomUUID());
        wo.setStatus(status);
        wo.setCurrentStage(new WorkflowEngine().resolveStage(status));
        Center c = new Center();
        c.setId(UUID.randomUUID());
        wo.setCenter(c);
        when(accessPolicy.requireReadable(wo.getId(), actor)).thenReturn(wo);
        return wo;
    }

    private static TransitionRequest to(TicketStatus target) {
        TransitionRequest r = new TransitionRequest();
        r.setTargetStatus(target);
        r.setNote("n");
        return r;
    }

    @Test
    @DisplayName("every (from, to, role): allowed exactly per table, else 422 with no write, history or notification")
    void fullMatrix() {
        List<String> mismatches = new ArrayList<>();
        int allowed = 0;
        for (TicketStatus from : TicketStatus.values()) {
            for (TicketStatus target : TicketStatus.values()) {
                for (UserRole role : UserRole.values()) {
                    reset(workOrderRepo, historyRepo, notifications);
                    User actor = user(role);
                    WorkOrder wo = ticket(from, actor);
                    boolean expected = expectedAllowed(from, target, role);
                    boolean ok;
                    try {
                        service.transition(wo.getId(), to(target), actor);
                        ok = true;
                    } catch (WorkflowException e) {
                        ok = false;
                    }
                    String label = role + " " + from + ">" + target;
                    if (ok != expected) mismatches.add(label + " expected " + (expected ? "allow" : "422"));
                    if (ok) {
                        allowed++;
                        // exactly one audit row per successful move, carrying actor and both statuses
                        ArgumentCaptor<TicketHistory> h = ArgumentCaptor.forClass(TicketHistory.class);
                        verify(historyRepo, times(1)).save(h.capture());
                        if (h.getValue().getFromStatus() != from || h.getValue().getToStatus() != target
                                || h.getValue().getActor() != actor) {
                            mismatches.add(label + " wrong history row");
                        }
                        if (wo.getStatus() != target) mismatches.add(label + " status not applied");
                    } else {
                        if (wo.getStatus() != from) mismatches.add(label + " status changed on failure");
                        verifyNoInteractions(historyRepo, notifications);
                        verify(workOrderRepo, never()).save(any());
                    }
                }
            }
        }
        assertThat(mismatches).isEmpty();
        assertThat(allowed).as("sanity: the table allows some moves").isGreaterThan(10);
    }

    @Test
    @DisplayName("repeating the current status is 422 for every role, including system_admin")
    void repeatIsRejected() {
        for (TicketStatus s : TicketStatus.values()) {
            for (UserRole role : UserRole.values()) {
                User actor = user(role);
                WorkOrder wo = ticket(s, actor);
                assertThatThrownBy(() -> service.transition(wo.getId(), to(s), actor))
                    .as("%s %s>%s", role, s, s)
                    .isInstanceOf(WorkflowException.class);
            }
        }
        verifyNoInteractions(historyRepo);
    }

    @Test
    @DisplayName("technician: only assigned->in_progress, in_progress->completed/waiting_materials")
    void technicianMoves() {
        Set<String> got = new java.util.TreeSet<>();
        for (TicketStatus from : TicketStatus.values()) {
            for (TicketStatus target : TicketStatus.values()) {
                if (expectedAllowed(from, target, UserRole.technician)) got.add(from.name() + ">" + target.name());
            }
        }
        assertThat(got).containsExactlyInAnyOrder(
            "ASSIGNED>IN_PROGRESS", "IN_PROGRESS>COMPLETED", "IN_PROGRESS>WAITING_MATERIALS");
        // and the technician can never cancel, close, assign or approve
        for (TicketStatus from : TicketStatus.values()) {
            for (TicketStatus target : Set.of(TicketStatus.CANCELLED, TicketStatus.CLOSED,
                    TicketStatus.ASSIGNED, TicketStatus.UNDER_REVIEW)) {
                User tech = user(UserRole.technician);
                WorkOrder wo = ticket(from, tech);
                assertThatThrownBy(() -> service.transition(wo.getId(), to(target), tech))
                    .isInstanceOf(WorkflowException.class);
            }
        }
    }

    @Test
    @DisplayName("a lock held by another user blocks transition (409 type) for every role incl. system_admin; own lock does not")
    void lockedByOtherBlocksEveryone() {
        for (UserRole role : UserRole.values()) {
            User holder = user(UserRole.control_room);
            User actor = user(role);
            WorkOrder wo = ticket(TicketStatus.NEW, actor);
            wo.setLocked(true);
            wo.setLockedBy(holder);
            assertThatThrownBy(() -> service.transition(wo.getId(), to(TicketStatus.UNDER_REVIEW), actor))
                .as(role.name())
                .isInstanceOf(TicketLockedException.class);
            assertThat(wo.getStatus()).isEqualTo(TicketStatus.NEW);
        }
        verifyNoInteractions(historyRepo);

        User cro = user(UserRole.control_room);
        WorkOrder own = ticket(TicketStatus.NEW, cro);
        own.setLocked(true);
        own.setLockedBy(cro);
        service.transition(own.getId(), to(TicketStatus.UNDER_REVIEW), cro);
        assertThat(own.getStatus()).isEqualTo(TicketStatus.UNDER_REVIEW);
    }

    @Test
    @DisplayName("the lock check runs before the workflow check (a locked ticket never reveals workflow rules)")
    void lockBeatsWorkflowValidation() {
        User holder = user(UserRole.control_room);
        User resident = user(UserRole.resident);
        WorkOrder wo = ticket(TicketStatus.NEW, resident);
        wo.setLocked(true);
        wo.setLockedBy(holder);
        assertThatThrownBy(() -> service.transition(wo.getId(), to(TicketStatus.CLOSED), resident))
            .isInstanceOf(TicketLockedException.class);
    }

    // ── lock / unlock audit rows (P4) ────────────────────

    @Test
    void lockWritesOneHistoryRowWithSameStatusAndNote() {
        User cro = user(UserRole.control_room);
        WorkOrder wo = ticket(TicketStatus.ASSIGNED, cro);

        service.lock(wo.getId(), cro);

        ArgumentCaptor<TicketHistory> h = ArgumentCaptor.forClass(TicketHistory.class);
        verify(historyRepo, times(1)).save(h.capture());
        assertThat(h.getValue().getFromStatus()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(h.getValue().getToStatus()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(h.getValue().getNote()).isEqualTo("locked");
        assertThat(h.getValue().getActor()).isSameAs(cro);
        assertThat(h.getValue().getActorRole()).isEqualTo(UserRole.control_room);
        assertThat(wo.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
    }

    @Test
    void secondLockIsConflictAndWritesNothing() {
        User a = user(UserRole.control_room);
        User b = user(UserRole.maintenance_manager);
        WorkOrder wo = ticket(TicketStatus.NEW, b);
        wo.setLocked(true);
        wo.setLockedBy(a);
        assertThatThrownBy(() -> service.lock(wo.getId(), b)).isInstanceOf(TicketLockedException.class);
        verifyNoInteractions(historyRepo);
        assertThat(wo.getLockedBy()).isSameAs(a);
    }

    @Test
    void unlockByOwnerAndBySystemAdminWriteOneRowEachButOtherRolesAreDenied() {
        User owner = user(UserRole.control_room);
        WorkOrder wo = ticket(TicketStatus.NEW, owner);
        wo.setLocked(true);
        wo.setLockedBy(owner);
        service.unlock(wo.getId(), owner);
        ArgumentCaptor<TicketHistory> h = ArgumentCaptor.forClass(TicketHistory.class);
        verify(historyRepo, times(1)).save(h.capture());
        assertThat(h.getValue().getNote()).isEqualTo("unlocked");
        assertThat(wo.isLocked()).isFalse();
        assertThat(wo.getLockedBy()).isNull();

        reset(historyRepo);
        User admin = user(UserRole.system_admin);
        WorkOrder wo2 = ticket(TicketStatus.NEW, admin);
        wo2.setLocked(true);
        wo2.setLockedBy(owner);
        service.unlock(wo2.getId(), admin);
        verify(historyRepo, times(1)).save(any());

        reset(historyRepo);
        User peer = user(UserRole.maintenance_manager);
        WorkOrder wo3 = ticket(TicketStatus.NEW, peer);
        wo3.setLocked(true);
        wo3.setLockedBy(owner);
        assertThatThrownBy(() -> service.unlock(wo3.getId(), peer)).isInstanceOf(AccessDeniedException.class);
        assertThat(wo3.isLocked()).isTrue();
        verifyNoInteractions(historyRepo);
    }

    @Test
    void unlockOfAnUnlockedTicketIsANoOpWithoutHistory() {
        User cro = user(UserRole.control_room);
        WorkOrder wo = ticket(TicketStatus.NEW, cro);
        service.unlock(wo.getId(), cro);
        verifyNoInteractions(historyRepo);
        verify(workOrderRepo, never()).save(any());
    }
}
