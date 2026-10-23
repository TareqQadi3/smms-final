package com.smms.service;

import com.smms.workflow.WorkOrderHistoryWriter;
import com.smms.repository.ControlRoomRepository;
import com.smms.repository.AssetRepository;
import com.smms.dto.request.CreateWorkOrderRequest;
import com.smms.dto.request.TransitionRequest;
import com.smms.entity.*;
import com.smms.enums.*;
import com.smms.mapper.WorkOrderMapper;
import com.smms.repository.*;
import com.smms.exception.ResourceNotFoundException;
import com.smms.security.TenantSecurityService;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.security.WorkOrderScope;
import com.smms.util.TenantContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

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
    private WorkOrderAccessPolicy accessPolicy;
    private TicketHistoryRepository historyRepo;
    private NotificationService notificationService;
    private UserRepository userRepo;
    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        workOrderRepo = mock(WorkOrderRepository.class);
        tenantSecurity = mock(TenantSecurityService.class);
        workflowEngine = mock(WorkflowEngine.class);
        mapper = mock(WorkOrderMapper.class);
        em = mock(EntityManager.class);
        accessPolicy = mock(WorkOrderAccessPolicy.class);
        historyRepo = mock(TicketHistoryRepository.class);
        notificationService = mock(NotificationService.class);
        userRepo = mock(UserRepository.class);
        service = new WorkOrderService(workOrderRepo, historyRepo, workflowEngine,
            notificationService, mapper, mock(PlanEnforcementService.class),
            tenantSecurity, accessPolicy, em, userRepo,
            new WorkOrderHistoryWriter(historyRepo), mock(ControlRoomRepository.class), mock(AssetRepository.class));
        when(mapper.toResponse(any(WorkOrder.class))).thenAnswer(i -> com.smms.dto.response.WorkOrderResponse.builder().build());
        // create() also looks for duplicate candidates; default to none
        when(workOrderRepo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
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
        verifyNoInteractions(accessPolicy);
    }

    @Test
    void lockByControlRoomChecksAccessAndSaves() {
        UUID id = UUID.randomUUID();
        WorkOrder wo = new WorkOrder();
        User actor = user(UserRole.control_room);
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo);

        service.lock(id, actor);

        verify(accessPolicy).requireReadable(id, actor);
        assertThat(wo.isLocked()).isTrue();
        assertThat(wo.getLockedBy()).isSameAs(actor);
        verify(workOrderRepo).save(wo);
    }

    @Test
    void technicianCannotLockUnassignedTicket() {
        UUID id = UUID.randomUUID();
        WorkOrder wo = new WorkOrder();
        User tech = user(UserRole.technician);
        // Technician behaviour changed from 403 to 404 for unassigned tickets (no existence oracle)
        when(accessPolicy.requireReadable(id, tech)).thenThrow(new ResourceNotFoundException("WorkOrder not found: " + id));

        assertThatThrownBy(() -> service.lock(id, tech)).isInstanceOf(ResourceNotFoundException.class);
        verify(workOrderRepo, never()).save(any());
    }

    // F5 generic transition bypass

    // M3: wrong-endpoint transitions are WorkflowException (422), no longer IllegalArgumentException (400)
    @Test
    void genericTransitionToAssignedRejected() {
        UUID id = UUID.randomUUID();
        WorkOrder wo = new WorkOrder();
        wo.setStatus(TicketStatus.UNDER_REVIEW);
        User cro = user(UserRole.control_room);
        when(accessPolicy.requireReadable(id, cro)).thenReturn(wo);
        TransitionRequest req = new TransitionRequest();
        req.setTargetStatus(TicketStatus.ASSIGNED);

        assertThatThrownBy(() -> service.transition(id, req, cro))
            .isInstanceOf(com.smms.exception.WorkflowException.class)
            .hasMessageContaining("/assign");
        assertThat(wo.getStatus()).isEqualTo(TicketStatus.UNDER_REVIEW);
        verify(workOrderRepo, never()).save(any());
    }

    @Test
    void dedicatedEndpointRules() {
        assertThatThrownBy(() -> WorkOrderService.rejectDedicatedEndpointTransition(
            TicketStatus.WAITING_APPROVAL, TicketStatus.IN_PROGRESS, UserRole.operating_company_manager))
            .isInstanceOf(com.smms.exception.WorkflowException.class);
        // system_admin keeps the UI path for approval
        WorkOrderService.rejectDedicatedEndpointTransition(
            TicketStatus.WAITING_APPROVAL, TicketStatus.IN_PROGRESS, UserRole.system_admin);
        assertThatThrownBy(() -> WorkOrderService.rejectDedicatedEndpointTransition(
            TicketStatus.COMPLETED, TicketStatus.IN_PROGRESS, UserRole.control_room))
            .isInstanceOf(com.smms.exception.WorkflowException.class);
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

    // DEF-005 policy wiring

    private interface Op { void run(WorkOrderService s, UUID id, User actor); }

    static Stream<Arguments> singleObjectOps() {
        TransitionRequest tr = new TransitionRequest();
        tr.setTargetStatus(TicketStatus.UNDER_REVIEW);
        return Stream.of(
            Arguments.of("getById", UserRole.control_room, (Op) (s, id, a) -> s.getById(id, a)),
            Arguments.of("getHistory", UserRole.control_room, (Op) (s, id, a) -> s.getHistory(id, a)),
            Arguments.of("transition", UserRole.control_room, (Op) (s, id, a) -> s.transition(id, tr, a)),
            Arguments.of("assign", UserRole.control_room, (Op) (s, id, a) -> s.assign(id, UUID.randomUUID(), "n", a)),
            Arguments.of("warehouse", UserRole.warehouse, (Op) (s, id, a) -> s.warehouseAction(id, true, "n", a)),
            Arguments.of("approval", UserRole.operating_company_manager,
                (Op) (s, id, a) -> s.approvalDecision(id, ApprovalDecision.approved, "n", a)),
            Arguments.of("close", UserRole.control_room, (Op) (s, id, a) -> s.close(id, "n", a)),
            Arguments.of("reopen", UserRole.control_room, (Op) (s, id, a) -> s.reopen(id, "n", a)),
            Arguments.of("lock", UserRole.control_room, (Op) (s, id, a) -> s.lock(id, a)),
            Arguments.of("unlock", UserRole.control_room, (Op) (s, id, a) -> s.unlock(id, a)));
    }

    @ParameterizedTest(name = "{0} out of scope is 404 and changes nothing")
    @MethodSource("singleObjectOps")
    void everySingleObjectPathUsesPolicyAndStopsWhenOutOfScope(String name, UserRole role, Op op) {
        UUID id = UUID.randomUUID();
        User actor = user(role);
        when(accessPolicy.requireReadable(id, actor)).thenThrow(new ResourceNotFoundException("WorkOrder not found: " + id));

        assertThatThrownBy(() -> op.run(service, id, actor)).isInstanceOf(ResourceNotFoundException.class);

        verify(accessPolicy).requireReadable(id, actor);
        verify(workOrderRepo, never()).save(any());
        verifyNoInteractions(historyRepo, notificationService, workflowEngine);
    }

    @Test
    void searchReturnsEmptyPageForNoneScope() {
        User other = user(UserRole.other);
        when(accessPolicy.resolveScope(other)).thenReturn(WorkOrderScope.none());

        Page<?> page = service.search(null, null, null, null, null, org.springframework.data.domain.PageRequest.of(0, 20), other);

        assertThat(page.getContent()).isEmpty();
        verify(workOrderRepo, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(accessPolicy, never()).specFor(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchUsesPolicySpec() {
        User actor = user(UserRole.resident);
        WorkOrderScope scope = WorkOrderScope.of(actor.getId(), null, null);
        Specification<WorkOrder> policySpec = mock(Specification.class);
        Specification<WorkOrder> combined = mock(Specification.class);
        when(accessPolicy.resolveScope(actor)).thenReturn(scope);
        when(accessPolicy.specFor(scope)).thenReturn(policySpec);
        when(policySpec.and(any())).thenReturn(combined);
        when(workOrderRepo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        service.search(UUID.randomUUID(), null, null, null, null, org.springframework.data.domain.PageRequest.of(0, 20), actor);

        verify(accessPolicy, times(1)).specFor(scope);
        // The caller filter is ANDed onto the policy spec, never the other way around
        verify(policySpec).and(any());
        verify(workOrderRepo, times(1)).findAll(eq(combined), any(Pageable.class));
    }

    @Test
    void createDeniedOutsideScopeDoesNotSave() {
        UUID crId = UUID.randomUUID();
        ControlRoom cr = new ControlRoom();
        cr.setId(crId);
        cr.setType(ControlRoomType.hospital);
        cr.setCenter(new Center());
        when(tenantSecurity.requireControlRoom(crId)).thenReturn(cr);
        User actor = user(UserRole.control_room);
        doThrow(new AccessDeniedException("no")).when(accessPolicy).assertCanCreateIn(cr, actor);
        CreateWorkOrderRequest req = new CreateWorkOrderRequest();
        req.setControlRoomId(crId);

        assertThatThrownBy(() -> service.create(req, actor)).isInstanceOf(AccessDeniedException.class);

        verify(workOrderRepo, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void createUsesControlRoomTenant() {
        UUID crId = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        ControlRoom cr = new ControlRoom();
        cr.setId(crId);
        cr.setTenantId(tenant);
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

        org.mockito.ArgumentCaptor<WorkOrder> cap = org.mockito.ArgumentCaptor.forClass(WorkOrder.class);
        verify(workOrderRepo).save(cap.capture());
        assertThat(TenantContext.get()).isNull();
        assertThat(cap.getValue().getTenantId()).isEqualTo(tenant);
    }

    private WorkOrder underReviewIn(Center center, UUID id, User actor) {
        WorkOrder wo = new WorkOrder();
        wo.setStatus(TicketStatus.UNDER_REVIEW);
        wo.setCenter(center);
        when(accessPolicy.requireReadable(id, actor)).thenReturn(wo);
        return wo;
    }

    private static Center center() {
        Center c = new Center();
        c.setId(UUID.randomUUID());
        return c;
    }

    @Test
    void assignRejectsTechnicianOfOtherCenter() {
        UUID id = UUID.randomUUID();
        User actor = user(UserRole.control_room);
        WorkOrder wo = underReviewIn(center(), id, actor);
        User tech = user(UserRole.technician);
        tech.setCenter(center());
        when(tenantSecurity.requireUser(tech.getId(), UserRole.technician)).thenReturn(tech);

        assertThatThrownBy(() -> service.assign(id, tech.getId(), "n", actor))
            .isInstanceOf(ResourceNotFoundException.class);

        assertThat(wo.getStatus()).isEqualTo(TicketStatus.UNDER_REVIEW);
        verify(workOrderRepo, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void assignRejectsTechnicianWithoutCenter() {
        UUID id = UUID.randomUUID();
        User actor = user(UserRole.control_room);
        underReviewIn(center(), id, actor);
        User tech = user(UserRole.technician);
        when(tenantSecurity.requireUser(tech.getId(), UserRole.technician)).thenReturn(tech);

        assertThatThrownBy(() -> service.assign(id, tech.getId(), "n", actor))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(workOrderRepo, never()).save(any());
    }

    @Test
    void assignRejectsInactiveTechnicianOfSameCenter() {
        UUID id = UUID.randomUUID();
        User actor = user(UserRole.control_room);
        Center c = center();
        underReviewIn(c, id, actor);
        User tech = user(UserRole.technician);
        tech.setCenter(c);
        tech.setActive(false);
        when(tenantSecurity.requireUser(tech.getId(), UserRole.technician)).thenReturn(tech);

        assertThatThrownBy(() -> service.assign(id, tech.getId(), "n", actor))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(workOrderRepo, never()).save(any());
    }

    @Test
    void assignAllowsTechnicianOfSameCenter() {
        UUID id = UUID.randomUUID();
        User actor = user(UserRole.control_room);
        Center c = center();
        WorkOrder wo = underReviewIn(c, id, actor);
        User tech = user(UserRole.technician);
        tech.setCenter(c);
        when(tenantSecurity.requireUser(tech.getId(), UserRole.technician)).thenReturn(tech);

        service.assign(id, tech.getId(), "n", actor);

        assertThat(wo.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(wo.getAssignedTo()).isSameAs(tech);
        verify(workOrderRepo).save(wo);
    }

    @Test
    void legitimateWorkflowStepsStillWorkInScope() {
        UUID id = UUID.randomUUID();
        // warehouse sends materials, approval decides, close and reopen all proceed once in scope
        User wh = user(UserRole.warehouse);
        WorkOrder w1 = new WorkOrder();
        w1.setStatus(TicketStatus.WAITING_MATERIALS);
        when(accessPolicy.requireReadable(id, wh)).thenReturn(w1);
        service.warehouseAction(id, true, "ok", wh);
        assertThat(w1.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);

        User opco = user(UserRole.operating_company_manager);
        WorkOrder w2 = new WorkOrder();
        w2.setStatus(TicketStatus.WAITING_APPROVAL);
        when(accessPolicy.requireReadable(id, opco)).thenReturn(w2);
        service.approvalDecision(id, ApprovalDecision.approved, "ok", opco);
        assertThat(w2.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);

        User cro = user(UserRole.control_room);
        WorkOrder w3 = new WorkOrder();
        w3.setStatus(TicketStatus.COMPLETED);
        when(accessPolicy.requireReadable(id, cro)).thenReturn(w3);
        service.reopen(id, "again", cro);
        assertThat(w3.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(w3.getReopenedCount()).isEqualTo(1);
    }

    // ── assignable technicians (technician picker) ──────────

    private static User technician(Center center, UUID tenant, boolean active, String name) {
        User t = user(UserRole.technician);
        t.setCenter(center);
        t.setTenantId(tenant);
        t.setActive(active);
        t.setFullName(name);
        return t;
    }

    @Test
    void assignableTechniciansOutOfScopeTicketIsNotFound() {
        UUID id = UUID.randomUUID();
        User actor = user(UserRole.maintenance_manager);
        when(accessPolicy.requireReadable(id, actor)).thenThrow(new ResourceNotFoundException("WorkOrder not found: " + id));

        assertThatThrownBy(() -> service.assignableTechnicians(id, actor)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(userRepo);
    }

    @Test
    void assignableTechniciansAreActiveSameCenterSameTenantOnlyWithMinimalFields() {
        UUID tenant = UUID.randomUUID();
        Center center = new Center();
        center.setId(UUID.randomUUID());
        WorkOrder wo = new WorkOrder();
        wo.setId(UUID.randomUUID());
        wo.setCenter(center);
        wo.setTenantId(tenant);
        User actor = user(UserRole.maintenance_manager);
        when(accessPolicy.requireReadable(wo.getId(), actor)).thenReturn(wo);
        User ok = technician(center, tenant, true, "Ok Tech");
        User inactive = technician(center, tenant, false, "Inactive");
        User otherTenant = technician(center, UUID.randomUUID(), true, "Other Tenant");
        when(userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(center.getId(), UserRole.technician))
            .thenReturn(java.util.List.of(ok, inactive, otherTenant));

        var out = service.assignableTechnicians(wo.getId(), actor);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getId()).isEqualTo(ok.getId());
        assertThat(out.get(0).getFullName()).isEqualTo("Ok Tech");
        verify(userRepo, never()).findAll();
    }

    @Test
    void assignableTechniciansOfTicketWithoutCenterIsEmpty() {
        WorkOrder wo = new WorkOrder();
        wo.setId(UUID.randomUUID());
        User actor = user(UserRole.control_room);
        when(accessPolicy.requireReadable(wo.getId(), actor)).thenReturn(wo);
        assertThat(service.assignableTechnicians(wo.getId(), actor)).isEmpty();
    }
}
