package com.smms.service;

import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import com.smms.repository.WorkOrderRepository;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.security.WorkOrderScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DashboardServiceTest {

    private WorkOrderRepository workOrderRepo;
    private WorkOrderAccessPolicy accessPolicy;
    private DashboardService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        workOrderRepo = mock(WorkOrderRepository.class);
        accessPolicy = mock(WorkOrderAccessPolicy.class);
        service = new DashboardService(workOrderRepo, accessPolicy);
        Specification<WorkOrder> base = mock(Specification.class);
        when(base.and(any())).thenReturn(mock(Specification.class));
        when(accessPolicy.specFor(any())).thenReturn(base);
    }

    private static User user(UserRole role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        return u;
    }

    @Test
    @SuppressWarnings("unchecked")
    void noneScopeGivesZerosWithoutQuerying() {
        User other = user(UserRole.other);
        when(accessPolicy.resolveScope(other)).thenReturn(WorkOrderScope.none());

        Map<String, Object> cr = service.controlRoomStats(other);
        Map<String, Object> mgmt = service.managementStats(other, UUID.randomUUID());

        assertThat(cr).hasSize(TicketStatus.values().length);
        assertThat(cr.values()).allMatch(v -> ((Long) v) == 0L);
        assertThat(mgmt.values()).allMatch(v -> ((Long) v) == 0L);
        verify(workOrderRepo, never()).count(any(Specification.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void controlRoomCountsOncePerStatusInScope() {
        User u = user(UserRole.control_room);
        WorkOrderScope scope = WorkOrderScope.of(u.getId(), null, null);
        when(accessPolicy.resolveScope(u)).thenReturn(scope);
        when(workOrderRepo.count(any(Specification.class))).thenReturn(3L);

        Map<String, Object> stats = service.controlRoomStats(u);

        assertThat(stats).hasSize(TicketStatus.values().length);
        assertThat(stats.values()).allMatch(v -> ((Long) v) == 3L);
        verify(workOrderRepo, times(TicketStatus.values().length)).count(any(Specification.class));
        verify(accessPolicy, times(1)).resolveScope(u);
        verify(accessPolicy, times(1)).specFor(scope);
    }

    @Test
    @SuppressWarnings("unchecked")
    void managementUsesPolicySpecEvenWithCenterFilter() {
        User u = user(UserRole.hospital_supervisor);
        WorkOrderScope scope = WorkOrderScope.of(u.getId(), null, null);
        when(accessPolicy.resolveScope(u)).thenReturn(scope);
        when(workOrderRepo.count(any(Specification.class))).thenReturn(1L);

        service.managementStats(u, UUID.randomUUID());

        verify(accessPolicy).specFor(scope);
        verify(workOrderRepo, times(TicketStatus.values().length)).count(any(Specification.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void technicianStatsUseScopeAndFourBuckets() {
        User t = user(UserRole.technician);
        WorkOrderScope scope = WorkOrderScope.of(null, t.getId(), null);
        when(accessPolicy.resolveScope(t)).thenReturn(scope);
        when(workOrderRepo.count(any(Specification.class))).thenReturn(2L);

        Map<String, Object> stats = service.technicianStats(t);

        assertThat(stats.keySet()).containsExactly("assigned", "in_progress", "waiting_materials", "completed");
        verify(accessPolicy).specFor(scope);
    }
}
