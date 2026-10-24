package com.smms.workflow;

import com.smms.entity.*;
import com.smms.enums.*;
import com.smms.dto.request.TransitionRequest;
import com.smms.dto.response.WorkOrderResponse;
import com.smms.mapper.WorkOrderMapper;
import com.smms.repository.*;
import com.smms.security.TenantSecurityService;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.service.NotificationService;
import com.smms.service.PlanEnforcementService;
import com.smms.service.WorkOrderService;
import com.smms.service.WorkflowEngine;
import com.smms.websocket.NotificationWebSocketHandler;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Who is notified at each workflow event: recipients are tenant/center scoped, active, never the actor. */
class WorkflowNotificationsTest {

    private final UUID tenant = UUID.randomUUID();
    private NotificationRepository notifRepo;
    private UserRepository userRepo;
    private UserControlRoomAccessRepository accessRepo;
    private NotificationService notifications;
    private Center center;
    private ControlRoom room;
    private WorkOrderRepository workOrderRepo;
    private WorkOrderAccessPolicy policy;
    private WorkOrderService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        notifRepo = mock(NotificationRepository.class);
        userRepo = mock(UserRepository.class);
        accessRepo = mock(UserControlRoomAccessRepository.class);
        when(notifRepo.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));
        notifications = new NotificationService(notifRepo, accessRepo, mock(NotificationWebSocketHandler.class), userRepo);
        center = new Center();
        center.setId(UUID.randomUUID());
        room = new ControlRoom();
        room.setId(UUID.randomUUID());
        workOrderRepo = mock(WorkOrderRepository.class);
        policy = mock(WorkOrderAccessPolicy.class);
        WorkOrderMapper mapper = mock(WorkOrderMapper.class);
        when(mapper.toResponse(any(WorkOrder.class))).thenAnswer(i -> WorkOrderResponse.builder().build());
        TicketHistoryRepository historyRepo = mock(TicketHistoryRepository.class);
        service = new WorkOrderService(workOrderRepo, historyRepo, new WorkflowEngine(), notifications, mapper,
            mock(PlanEnforcementService.class), mock(TenantSecurityService.class), policy, mock(EntityManager.class),
            userRepo, new WorkOrderHistoryWriter(historyRepo), mock(ControlRoomRepository.class),
            mock(AssetRepository.class), mock(DepartmentRepository.class), mock(InfoRequestRepository.class),
            mock(EscalationRepository.class));
        when(workOrderRepo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
    }

    private User user(UserRole role, boolean active, UUID t) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        u.setActive(active);
        u.setTenantId(t);
        return u;
    }

    private WorkOrder wo(TicketStatus s, User requester, User tech) {
        WorkOrder w = new WorkOrder();
        w.setId(UUID.randomUUID());
        w.setTicketNumber("WO-9");
        w.setTenantId(tenant);
        w.setCenter(center);
        w.setControlRoom(room);
        w.setStatus(s);
        w.setCurrentStage(s.getDbValue());
        w.setRequestedBy(requester);
        w.setAssignedTo(tech);
        return w;
    }

    private List<User> notified() {
        var cap = org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(notifRepo, atLeast(0)).save(cap.capture());
        return cap.getAllValues().stream().map(Notification::getUser).toList();
    }

    private List<String> types() {
        var cap = org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(notifRepo, atLeast(0)).save(cap.capture());
        return cap.getAllValues().stream().map(Notification::getType).toList();
    }

    private void transition(WorkOrder w, User actor, TicketStatus to) {
        when(policy.requireReadable(w.getId(), actor)).thenReturn(w);
        TransitionRequest r = new TransitionRequest();
        r.setTargetStatus(to);
        r.setNote("n");
        service.transition(w.getId(), r, actor);
    }

    @Test
    void waitingMaterialsNotifiesTheCenterWarehouseOnly() {
        User tech = user(UserRole.technician, true, tenant);
        User wh = user(UserRole.warehouse, true, tenant);
        when(userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(center.getId(), UserRole.warehouse))
            .thenReturn(List.of(wh, user(UserRole.warehouse, false, tenant), user(UserRole.warehouse, true, UUID.randomUUID())));
        WorkOrder w = wo(TicketStatus.IN_PROGRESS, user(UserRole.department, true, tenant), tech);

        transition(w, tech, TicketStatus.WAITING_MATERIALS);

        assertThat(notified()).containsExactly(wh);
        assertThat(types()).containsExactly("MATERIALS_REQUIRED");
    }

    @Test
    void completedNotifiesControlRoomOperatorsAndTheRequester() {
        User tech = user(UserRole.technician, true, tenant);
        User requester = user(UserRole.department, true, tenant);
        User op = user(UserRole.control_room, true, tenant);
        UserControlRoomAccess a1 = new UserControlRoomAccess();
        a1.setUser(op);
        UserControlRoomAccess a2 = new UserControlRoomAccess();
        a2.setUser(user(UserRole.control_room, false, tenant)); // inactive
        UserControlRoomAccess a3 = new UserControlRoomAccess();
        a3.setUser(user(UserRole.resident, true, tenant)); // holds a room grant but is not an operator
        when(accessRepo.findByControlRoomId(room.getId())).thenReturn(List.of(a1, a2, a3));
        WorkOrder w = wo(TicketStatus.IN_PROGRESS, requester, tech);

        transition(w, tech, TicketStatus.COMPLETED);

        assertThat(notified()).containsExactlyInAnyOrder(op, requester);
        assertThat(types()).containsOnly("WORK_COMPLETED");
    }

    @Test
    void requesterIsNotNotifiedOfTheirOwnAction() {
        User cr = user(UserRole.control_room, true, tenant);
        WorkOrder w = wo(TicketStatus.UNDER_REVIEW, cr, null); // the operator is also the requester
        transition(w, cr, TicketStatus.CANCELLED);
        assertThat(notified()).isEmpty();
    }

    @Test
    void approvalGrantedAndMaterialsProvidedNotifyTheTechnician() {
        User tech = user(UserRole.technician, true, tenant);
        User ocm = user(UserRole.operating_company_manager, true, tenant);
        User wh = user(UserRole.warehouse, true, tenant);
        WorkOrder approved = wo(TicketStatus.WAITING_APPROVAL, user(UserRole.department, true, tenant), tech);
        when(policy.requireReadable(approved.getId(), ocm)).thenReturn(approved);
        service.approvalDecision(approved.getId(), ApprovalDecision.approved, "ok", ocm);
        assertThat(notified()).containsExactly(tech);
        assertThat(types()).containsExactly("APPROVAL_GRANTED");

        clearInvocations(notifRepo);
        WorkOrder purchase = wo(TicketStatus.WAITING_APPROVAL, user(UserRole.department, true, tenant), tech);
        when(policy.requireReadable(purchase.getId(), ocm)).thenReturn(purchase);
        service.approvalDecision(purchase.getId(), ApprovalDecision.purchase_request, "buy", ocm);
        assertThat(types()).containsExactly("APPROVAL_GRANTED");

        clearInvocations(notifRepo);
        WorkOrder waiting = wo(TicketStatus.WAITING_MATERIALS, user(UserRole.department, true, tenant), tech);
        when(policy.requireReadable(waiting.getId(), wh)).thenReturn(waiting);
        service.warehouseAction(waiting.getId(), true, "here", wh);
        assertThat(notified()).containsExactly(tech);
        assertThat(types()).containsExactly("MATERIALS_PROVIDED");
    }

    @Test
    void closeNotifiesTheRequesterAndReopenTheTechnician() {
        User tech = user(UserRole.technician, true, tenant);
        User requester = user(UserRole.resident, true, tenant);
        User cr = user(UserRole.control_room, true, tenant);
        WorkOrder done = wo(TicketStatus.COMPLETED, requester, tech);
        when(policy.requireReadable(done.getId(), cr)).thenReturn(done);
        service.close(done.getId(), "ok", cr);
        assertThat(notified()).containsExactly(requester);
        assertThat(types()).containsExactly("CLOSED");

        clearInvocations(notifRepo);
        WorkOrder again = wo(TicketStatus.COMPLETED, requester, tech);
        again.setRequesterConfirmation(RequesterConfirmation.disputed);
        when(policy.requireReadable(again.getId(), cr)).thenReturn(again);
        service.reopen(again.getId(), "still broken", cr);
        assertThat(notified()).containsExactly(tech);
        assertThat(again.getRequesterConfirmation()).isNull(); // the verdict starts over
        assertThat(again.getRequesterConfirmedAt()).isNull();
    }

    @Test
    void approverFallsBackToMaintenanceManagersWhenTheCenterHasNoOpco() {
        User mm = user(UserRole.maintenance_manager, true, tenant);
        when(userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(center.getId(), UserRole.operating_company_manager))
            .thenReturn(List.of(user(UserRole.operating_company_manager, false, tenant)));
        when(userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(center.getId(), UserRole.maintenance_manager))
            .thenReturn(List.of(mm, user(UserRole.maintenance_manager, true, UUID.randomUUID())));

        notifications.notifyApprover(wo(TicketStatus.WAITING_APPROVAL, null, null));

        assertThat(notified()).containsExactly(mm);
        assertThat(types()).containsExactly("APPROVAL_REQUIRED");
    }

    @Test
    void approverDoesNotFallBackWhenAnOpcoExists() {
        User ocm = user(UserRole.operating_company_manager, true, tenant);
        when(userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(center.getId(), UserRole.operating_company_manager))
            .thenReturn(List.of(ocm));
        notifications.notifyApprover(wo(TicketStatus.WAITING_APPROVAL, null, null));
        assertThat(notified()).containsExactly(ocm);
        verify(userRepo, never()).findAllByCenterIdAndRoleAndDeletedAtIsNull(any(), eq(UserRole.maintenance_manager));
    }

    @Test
    @SuppressWarnings("unchecked")
    void levelTwoReachesRegionSupervisorsAndOpcoOfTheCenterInTheSameTenantOnly() {
        Region region = new Region();
        region.setId(UUID.randomUUID());
        center.setRegion(region);
        User rs = user(UserRole.region_supervisor, true, tenant);
        User ocm = user(UserRole.operating_company_manager, true, tenant);
        when(userRepo.findAll(any(Specification.class))).thenReturn(List.of(rs, user(UserRole.region_supervisor, false, tenant)));
        when(userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(center.getId(), UserRole.operating_company_manager))
            .thenReturn(List.of(ocm, user(UserRole.operating_company_manager, true, UUID.randomUUID())));

        int n = notifications.notifyEscalation(wo(TicketStatus.IN_PROGRESS, null, null), 2);

        assertThat(n).isEqualTo(2);
        assertThat(notified()).containsExactlyInAnyOrder(rs, ocm);
    }

    @Test
    void emergencyReachesManagersSupervisorsAndOperatorsButNotTheActor() {
        User actor = user(UserRole.hospital_supervisor, true, tenant);
        User mm = user(UserRole.maintenance_manager, true, tenant);
        User op = user(UserRole.control_room, true, tenant);
        when(userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(center.getId(), UserRole.maintenance_manager)).thenReturn(List.of(mm));
        when(userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(center.getId(), UserRole.hospital_supervisor)).thenReturn(List.of(actor));
        UserControlRoomAccess a = new UserControlRoomAccess();
        a.setUser(op);
        when(accessRepo.findByControlRoomId(room.getId())).thenReturn(List.of(a));

        int n = notifications.notifyEmergency(wo(TicketStatus.IN_PROGRESS, null, null), actor);

        assertThat(n).isEqualTo(2);
        assertThat(notified()).containsExactlyInAnyOrder(mm, op);
    }
}
