package com.smms.service;

import com.smms.dto.request.CreateWorkOrderRequest;
import com.smms.dto.request.TransitionRequest;
import com.smms.entity.*;
import com.smms.enums.*;
import com.smms.mapper.WorkOrderMapper;
import com.smms.repository.*;
import com.smms.security.TenantSecurityService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkOrderServiceTest {

    private WorkOrderRepository workOrderRepo;
    private TenantSecurityService tenantSecurity;
    private WorkflowEngine workflowEngine;
    private WorkOrderMapper mapper;
    private EntityManager em;
    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        workOrderRepo = mock(WorkOrderRepository.class);
        tenantSecurity = mock(TenantSecurityService.class);
        workflowEngine = mock(WorkflowEngine.class);
        mapper = mock(WorkOrderMapper.class);
        em = mock(EntityManager.class);
        service = new WorkOrderService(workOrderRepo, mock(TicketHistoryRepository.class),
            mock(UserControlRoomAccessRepository.class), workflowEngine,
            mock(NotificationService.class), mapper, mock(PlanEnforcementService.class),
            tenantSecurity, em);
    }

    private static User user(UserRole role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        return u;
    }

    // F4 lock

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"resident", "department", "other", "warehouse"})
    void lockDeniedForNonWorkingRoles(UserRole role) {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.lock(id, user(role))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.unlock(id, user(role))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(tenantSecurity);
    }

    @Test
    void lockByControlRoomChecksAccessAndSaves() {
        UUID id = UUID.randomUUID();
        WorkOrder wo = new WorkOrder();
        when(tenantSecurity.requireWorkOrder(id)).thenReturn(wo);
        User actor = user(UserRole.control_room);

        service.lock(id, actor);

        verify(tenantSecurity).assertWorkOrderAccess(wo, actor);
        assertThat(wo.isLocked()).isTrue();
        assertThat(wo.getLockedBy()).isSameAs(actor);
        verify(workOrderRepo).save(wo);
    }

    @Test
    void technicianCannotLockUnassignedTicket() {
        UUID id = UUID.randomUUID();
        WorkOrder wo = new WorkOrder();
        when(tenantSecurity.requireWorkOrder(id)).thenReturn(wo);
        User tech = user(UserRole.technician);
        doThrow(new AccessDeniedException("Not assigned")).when(tenantSecurity).assertWorkOrderAccess(wo, tech);

        assertThatThrownBy(() -> service.lock(id, tech)).isInstanceOf(AccessDeniedException.class);
        verify(workOrderRepo, never()).save(any());
    }

    // F5 generic transition bypass

    @Test
    void genericTransitionToAssignedRejected() {
        UUID id = UUID.randomUUID();
        WorkOrder wo = new WorkOrder();
        wo.setStatus(TicketStatus.UNDER_REVIEW);
        when(tenantSecurity.requireWorkOrder(id)).thenReturn(wo);
        TransitionRequest req = new TransitionRequest();
        req.setTargetStatus(TicketStatus.ASSIGNED);

        assertThatThrownBy(() -> service.transition(id, req, user(UserRole.control_room)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("/assign");
        assertThat(wo.getStatus()).isEqualTo(TicketStatus.UNDER_REVIEW);
        verify(workOrderRepo, never()).save(any());
    }

    @Test
    void dedicatedEndpointRules() {
        assertThatThrownBy(() -> WorkOrderService.rejectDedicatedEndpointTransition(
            TicketStatus.WAITING_APPROVAL, TicketStatus.IN_PROGRESS, UserRole.operating_company_manager))
            .isInstanceOf(IllegalArgumentException.class);
        // system_admin keeps the UI path for approval
        WorkOrderService.rejectDedicatedEndpointTransition(
            TicketStatus.WAITING_APPROVAL, TicketStatus.IN_PROGRESS, UserRole.system_admin);
        assertThatThrownBy(() -> WorkOrderService.rejectDedicatedEndpointTransition(
            TicketStatus.COMPLETED, TicketStatus.IN_PROGRESS, UserRole.control_room))
            .isInstanceOf(IllegalArgumentException.class);
        // ordinary moves untouched
        WorkOrderService.rejectDedicatedEndpointTransition(
            TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, UserRole.technician);
        WorkOrderService.rejectDedicatedEndpointTransition(
            TicketStatus.NEW, TicketStatus.UNDER_REVIEW, UserRole.control_room);
    }

    // F6 create refresh

    @Test
    void createFlushesAndRefreshesBeforeMapping() {
        UUID crId = UUID.randomUUID();
        ControlRoom cr = new ControlRoom();
        cr.setId(crId);
        cr.setType(ControlRoomType.hospital);
        cr.setCenter(new Center());
        when(tenantSecurity.requireControlRoom(crId)).thenReturn(cr);
        when(workOrderRepo.save(any(WorkOrder.class))).thenAnswer(i -> i.getArgument(0));

        CreateWorkOrderRequest req = new CreateWorkOrderRequest();
        req.setControlRoomId(crId);
        req.setSubType(SubType.electrical);
        req.setTitle("t");
        req.setPriority(TicketPriority.medium);

        service.create(req, user(UserRole.system_admin));

        InOrder order = Mockito.inOrder(workOrderRepo, em, mapper);
        order.verify(workOrderRepo).save(any(WorkOrder.class));
        order.verify(workOrderRepo).flush();
        order.verify(em).refresh(any(WorkOrder.class));
        order.verify(mapper).toResponse(any(WorkOrder.class));
    }
}
