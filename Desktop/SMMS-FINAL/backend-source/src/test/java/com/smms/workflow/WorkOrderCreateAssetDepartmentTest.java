package com.smms.workflow;

import com.smms.dto.request.CreateWorkOrderRequest;
import com.smms.dto.response.WorkOrderResponse;
import com.smms.entity.*;
import com.smms.enums.*;
import com.smms.exception.ResourceNotFoundException;
import com.smms.mapper.WorkOrderMapper;
import com.smms.repository.*;
import com.smms.security.TenantSecurityService;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.service.NotificationService;
import com.smms.service.PlanEnforcementService;
import com.smms.service.WorkOrderService;
import com.smms.service.WorkflowEngine;
import com.smms.support.Fixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** create(): optional asset (same tenant AND center) and the department derived from the requester. */
class WorkOrderCreateAssetDepartmentTest {

    private final UUID tenant = UUID.randomUUID();
    private WorkOrderRepository workOrderRepo;
    private AssetRepository assetRepo;
    private DepartmentRepository departmentRepo;
    private TenantSecurityService tenantSecurity;
    private WorkOrderService service;
    private Center center, other;
    private ControlRoom room;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        workOrderRepo = mock(WorkOrderRepository.class);
        assetRepo = mock(AssetRepository.class);
        departmentRepo = mock(DepartmentRepository.class);
        tenantSecurity = mock(TenantSecurityService.class);
        WorkOrderMapper mapper = mock(WorkOrderMapper.class);
        when(mapper.toResponse(any(WorkOrder.class))).thenAnswer(i -> WorkOrderResponse.builder().build());
        TicketHistoryRepository historyRepo = mock(TicketHistoryRepository.class);
        service = new WorkOrderService(workOrderRepo, historyRepo, new WorkflowEngine(), mock(NotificationService.class),
            mapper, mock(PlanEnforcementService.class), tenantSecurity, mock(WorkOrderAccessPolicy.class),
            mock(EntityManager.class), mock(UserRepository.class), new WorkOrderHistoryWriter(historyRepo),
            mock(ControlRoomRepository.class), assetRepo, departmentRepo, mock(InfoRequestRepository.class),
            mock(EscalationRepository.class));
        when(workOrderRepo.save(any(WorkOrder.class))).thenAnswer(i -> i.getArgument(0));
        when(workOrderRepo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        Region region = Fixtures.region(tenant);
        center = Fixtures.center(region, tenant);
        other = Fixtures.center(region, tenant);
        room = Fixtures.room(center, tenant);
        room.setType(ControlRoomType.hospital);
        when(tenantSecurity.requireControlRoom(room.getId())).thenReturn(room);
    }

    private CreateWorkOrderRequest req(UUID assetId) {
        CreateWorkOrderRequest r = new CreateWorkOrderRequest();
        r.setControlRoomId(room.getId());
        r.setSubType(SubType.electrical);
        r.setTitle("t");
        r.setPriority(TicketPriority.medium);
        r.setAssetId(assetId);
        return r;
    }

    private Asset asset(Center c) {
        Asset a = new Asset();
        a.setId(UUID.randomUUID());
        a.setCenter(c);
        a.setTenantId(tenant);
        return a;
    }

    private WorkOrder saved() {
        var cap = org.mockito.ArgumentCaptor.forClass(WorkOrder.class);
        verify(workOrderRepo).save(cap.capture());
        return cap.getValue();
    }

    @Test
    void assetOfTheSameCenterIsLinked() {
        Asset a = asset(center);
        when(assetRepo.findByIdAndTenantIdAndDeletedAtIsNull(a.getId(), tenant)).thenReturn(Optional.of(a));
        service.create(req(a.getId()), Fixtures.user(UserRole.control_room, center, null, tenant));
        assertThat(saved().getAsset()).isSameAs(a);
    }

    @Test
    void assetOfAnotherCenterOrTenantOrMissingIsTheSame404() {
        Asset foreignCenter = asset(other);
        when(assetRepo.findByIdAndTenantIdAndDeletedAtIsNull(foreignCenter.getId(), tenant)).thenReturn(Optional.of(foreignCenter));
        UUID unknown = UUID.randomUUID(); // other tenant / deleted: the tenant-scoped finder returns empty
        when(assetRepo.findByIdAndTenantIdAndDeletedAtIsNull(unknown, tenant)).thenReturn(Optional.empty());
        User cr = Fixtures.user(UserRole.control_room, center, null, tenant);
        assertThatThrownBy(() -> service.create(req(foreignCenter.getId()), cr)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.create(req(unknown), cr)).isInstanceOf(ResourceNotFoundException.class);
        verify(workOrderRepo, never()).save(any());
    }

    @Test
    void departmentIsDerivedFromTheDepartmentRequesterNotTheClient() {
        Department d = Department.builder().center(center).name("D").nameAr("D").code("D").build();
        d.setId(UUID.randomUUID());
        d.setTenantId(tenant);
        User dep = Fixtures.user(UserRole.department, center, null, tenant);
        dep.setDepartment(d);
        when(departmentRepo.findByIdAndTenantIdAndDeletedAtIsNull(d.getId(), tenant)).thenReturn(Optional.of(d));
        service.create(req(null), dep);
        assertThat(saved().getDepartment()).isSameAs(d);
    }

    @Test
    void noDepartmentWhenLinkIsMissingForeignOrNotADepartmentUser() {
        User dep = Fixtures.user(UserRole.department, center, null, tenant);
        service.create(req(null), dep); // not linked
        assertThat(saved().getDepartment()).isNull();

        Department elsewhere = Department.builder().center(other).name("D").nameAr("D").code("D").build();
        elsewhere.setId(UUID.randomUUID());
        elsewhere.setTenantId(tenant);
        User linkedElsewhere = Fixtures.user(UserRole.department, center, null, tenant);
        linkedElsewhere.setDepartment(elsewhere);
        when(departmentRepo.findByIdAndTenantIdAndDeletedAtIsNull(elsewhere.getId(), tenant)).thenReturn(Optional.of(elsewhere));
        clearInvocations(workOrderRepo);
        service.create(req(null), linkedElsewhere);
        assertThat(saved().getDepartment()).isNull(); // different center: not applied

        User cr = Fixtures.user(UserRole.control_room, center, null, tenant);
        cr.setDepartment(elsewhere);
        clearInvocations(workOrderRepo);
        service.create(req(null), cr);
        assertThat(saved().getDepartment()).isNull(); // only department users
        verify(departmentRepo, never()).findByIdAndTenantIdAndDeletedAtIsNull(any(), eq(UUID.randomUUID()));
    }
}
