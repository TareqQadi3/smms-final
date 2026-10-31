package com.smms.pm;

import com.smms.entity.*;
import com.smms.enums.SubType;
import com.smms.enums.TicketPriority;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.PlanLimitExceededException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.repository.AssetRepository;
import com.smms.repository.UserRepository;
import com.smms.repository.WorkOrderRepository;
import com.smms.service.NotificationService;
import com.smms.service.WorkOrderService;
import com.smms.support.Fixtures;
import com.smms.workflow.GeneratedWorkOrderSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PmGenerationServiceTest {

    private MaintenancePlanRepository planRepo;
    private MaintenancePlanRunRepository runRepo;
    private AssetRepository assetRepo;
    private UserRepository userRepo;
    private WorkOrderRepository workOrderRepo;
    private WorkOrderService workOrderService;
    private NotificationService notifications;
    private PmGenerationService service;

    private final UUID tenant = UUID.randomUUID();
    private final UUID otherTenant = UUID.randomUUID();
    private final LocalDate today = LocalDate.parse("2025-03-10");
    private Center center;
    private ControlRoom room;
    private Asset asset;
    private User owner;
    private MaintenancePlan plan;

    @BeforeEach
    void setUp() {
        planRepo = mock(MaintenancePlanRepository.class);
        runRepo = mock(MaintenancePlanRunRepository.class);
        assetRepo = mock(AssetRepository.class);
        userRepo = mock(UserRepository.class);
        workOrderRepo = mock(WorkOrderRepository.class);
        workOrderService = mock(WorkOrderService.class);
        notifications = mock(NotificationService.class);
        service = new PmGenerationService(planRepo, runRepo, assetRepo, userRepo, workOrderRepo, workOrderService,
            notifications, mock(PlatformTransactionManager.class), new PmProperties());

        Region region = Fixtures.region(tenant);
        center = Fixtures.center(region, tenant);
        room = Fixtures.room(center, tenant);
        owner = Fixtures.user(UserRole.maintenance_manager, center, region, tenant);
        asset = Asset.builder().center(center).name("Chiller 1").nameAr("x").location("Roof").build();
        asset.setId(UUID.randomUUID());
        asset.setTenantId(tenant);

        plan = new MaintenancePlan();
        plan.setId(UUID.randomUUID());
        plan.setTenantId(tenant);
        plan.setCenter(center);
        plan.setAsset(asset);
        plan.setControlRoom(room);
        plan.setOwner(owner);
        plan.setSubType(SubType.hvac);
        plan.setPriority(TicketPriority.high);
        plan.setTitle("Quarterly service");
        plan.setInstructions("Check filters");
        plan.setFrequencyUnit(FrequencyUnit.month);
        plan.setFrequencyInterval(1);
        plan.setStartDate(LocalDate.parse("2025-01-31"));
        plan.setNextDueDate(LocalDate.parse("2025-01-31"));
        plan.setActive(true);

        when(planRepo.lockForGeneration(plan.getId(), tenant)).thenReturn(Optional.of(plan));
        when(planRepo.lockWaiting(plan.getId(), tenant)).thenReturn(Optional.of(plan));
        when(planRepo.findByIdAndTenantIdAndDeletedAtIsNull(plan.getId(), tenant)).thenReturn(Optional.of(plan));
        when(runRepo.claim(any(), eq(tenant), eq(plan.getId()), any(), anyString())).thenReturn(1);
        when(assetRepo.findByIdAndTenantIdAndDeletedAtIsNull(asset.getId(), tenant)).thenReturn(Optional.of(asset));
        when(userRepo.findByIdAndTenantIdAndDeletedAtIsNull(owner.getId(), tenant)).thenReturn(Optional.of(owner));
        WorkOrder wo = new WorkOrder();
        wo.setId(UUID.randomUUID());
        when(workOrderService.createGenerated(any())).thenReturn(wo);
    }

    @Test
    void generatesThroughCreateGeneratedLinksTheRunAndAdvancesWithoutDrift() {
        PmGenerationService.Result r = service.generate(plan.getId(), tenant, today, false);

        assertThat(r.outcome()).isEqualTo(PmGenerationService.Outcome.GENERATED);
        ArgumentCaptor<GeneratedWorkOrderSpec> spec = ArgumentCaptor.forClass(GeneratedWorkOrderSpec.class);
        verify(workOrderService).createGenerated(spec.capture());
        assertThat(spec.getValue().tenantId()).isEqualTo(tenant);
        assertThat(spec.getValue().controlRoomId()).isEqualTo(room.getId());
        assertThat(spec.getValue().assetId()).isEqualTo(asset.getId());
        assertThat(spec.getValue().requester()).isSameAs(owner);
        assertThat(spec.getValue().subType()).isEqualTo(SubType.hvac);
        assertThat(spec.getValue().priority()).isEqualTo(TicketPriority.high);
        assertThat(spec.getValue().title()).contains("Quarterly service").contains("Chiller 1");
        verify(runRepo).attachWorkOrder(tenant, plan.getId(), LocalDate.parse("2025-01-31"), r.workOrderId());
        assertThat(plan.getNextDueDate()).isEqualTo(LocalDate.parse("2025-02-28"));
        verify(planRepo).saveAndFlush(plan);
    }

    @Test
    void duplicatePeriodCreatesNoSecondWorkOrder() {
        when(runRepo.claim(any(), eq(tenant), eq(plan.getId()), any(), anyString())).thenReturn(0);

        PmGenerationService.Result r = service.generate(plan.getId(), tenant, today, false);

        assertThat(r.outcome()).isEqualTo(PmGenerationService.Outcome.ALREADY_GENERATED);
        verifyNoInteractions(workOrderService);
        // the period is covered, so the plan steps over it and is not stuck
        assertThat(plan.getNextDueDate()).isEqualTo(LocalDate.parse("2025-02-28"));
    }

    @Test
    void manualGenerateOfAnExistingPeriodIs409() {
        when(runRepo.claim(any(), eq(tenant), eq(plan.getId()), any(), anyString())).thenReturn(0);
        assertThatThrownBy(() -> service.generate(plan.getId(), tenant, today, true)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(workOrderService);
        assertThat(plan.getNextDueDate()).isEqualTo(LocalDate.parse("2025-01-31"));
    }

    @Test
    void manualGenerateIgnoresTheLeadWindowButNotInactivity() {
        plan.setNextDueDate(LocalDate.parse("2025-06-30"));
        assertThat(service.generate(plan.getId(), tenant, today, true).outcome()).isEqualTo(PmGenerationService.Outcome.GENERATED);
        plan.setActive(false);
        assertThatThrownBy(() -> service.generate(plan.getId(), tenant, today, true)).isInstanceOf(WorkflowException.class);
    }

    @Test
    void notYetDueAndInactivePlansAreLeftAlone() {
        plan.setNextDueDate(LocalDate.parse("2025-03-14"));
        plan.setLeadDays(3);
        assertThat(service.generate(plan.getId(), tenant, today, false).outcome()).isEqualTo(PmGenerationService.Outcome.NOT_DUE);
        plan.setLeadDays(4);
        assertThat(service.generate(plan.getId(), tenant, today, false).outcome()).isEqualTo(PmGenerationService.Outcome.GENERATED);

        plan.setActive(false);
        assertThat(service.generate(plan.getId(), tenant, today, false).outcome()).isEqualTo(PmGenerationService.Outcome.INACTIVE);
        verify(workOrderService, times(1)).createGenerated(any());
    }

    @Test
    void aPlanLockedByAnotherPassIsSkipped() {
        when(planRepo.lockForGeneration(plan.getId(), tenant)).thenReturn(Optional.empty());
        assertThat(service.generate(plan.getId(), tenant, today, false).outcome()).isEqualTo(PmGenerationService.Outcome.LOCKED);
        verifyNoInteractions(runRepo, workOrderService);
    }

    @Test
    void aPlanOfAnotherTenantIsNeverTouched() {
        // the scheduler names the plan's own tenant; asking with a foreign tenant finds nothing
        assertThat(service.generate(plan.getId(), otherTenant, today, false).outcome())
            .isEqualTo(PmGenerationService.Outcome.LOCKED);
        verify(planRepo).lockForGeneration(plan.getId(), otherTenant);
        verifyNoInteractions(workOrderService);
    }

    @Test
    void crossTenantAssetFailsTheRunAndNotifiesTheCenterManagersOnce() {
        when(assetRepo.findByIdAndTenantIdAndDeletedAtIsNull(asset.getId(), tenant)).thenReturn(Optional.empty());
        User otherMm = Fixtures.user(UserRole.maintenance_manager, center, null, otherTenant);
        User inactiveMm = Fixtures.user(UserRole.maintenance_manager, center, null, tenant);
        inactiveMm.setActive(false);
        when(userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(center.getId(), UserRole.maintenance_manager))
            .thenReturn(List.of(owner, otherMm, inactiveMm));
        when(runRepo.insertFailed(any(), eq(tenant), eq(plan.getId()), any(), eq("scheduler"), anyString())).thenReturn(1, 0);

        PmGenerationService.Result first = service.generate(plan.getId(), tenant, today, false);
        PmGenerationService.Result second = service.generate(plan.getId(), tenant, today, false);

        assertThat(first.outcome()).isEqualTo(PmGenerationService.Outcome.FAILED);
        assertThat(first.error()).isEqualTo("Asset not found");
        assertThat(second.outcome()).isEqualTo(PmGenerationService.Outcome.FAILED);
        verifyNoInteractions(workOrderService);
        // only the active same-tenant manager, and only for the first (new) failure
        verify(notifications, times(1)).notifyUser(eq(owner), isNull(), eq("PM_FAILED"), anyString());
        verify(notifications, never()).notifyUser(eq(otherMm), any(), anyString(), anyString());
        verify(notifications, never()).notifyUser(eq(inactiveMm), any(), anyString(), anyString());
        verify(runRepo).updateFailedError(eq(tenant), eq(plan.getId()), any(), anyString());
        assertThat(plan.getNextDueDate()).isEqualTo(LocalDate.parse("2025-01-31")); // stays due
    }

    @Test
    void failureMessagesAreSanitised() {
        doThrow(new PlanLimitExceededException("Ticket limit reached\nline2")).when(workOrderService).createGenerated(any());
        PmGenerationService.Result limit = service.generate(plan.getId(), tenant, today, false);
        assertThat(limit.error()).isEqualTo("Ticket limit reached line2");

        doThrow(new RuntimeException("ERROR: duplicate key (tenant_id)=(secret)")).when(workOrderService).createGenerated(any());
        PmGenerationService.Result unexpected = service.generate(plan.getId(), tenant, today, false);
        assertThat(unexpected.error()).doesNotContain("secret").contains("RuntimeException");

        assertThat(PmGenerationService.sanitize(new IllegalArgumentException("x".repeat(900)))).hasSize(500);
        assertThat(PmGenerationService.sanitize(new ResourceNotFoundException("Control room not found")))
            .isEqualTo("Control room not found");
    }

    @Test
    void decommissionedAssetFailsTheRun() {
        asset.setDecommissionedAt(java.time.Instant.now());
        PmGenerationService.Result r = service.generate(plan.getId(), tenant, today, false);
        assertThat(r.outcome()).isEqualTo(PmGenerationService.Outcome.FAILED);
        assertThat(r.error()).contains("decommissioned");
        verifyNoInteractions(workOrderService);
    }

    @Test
    void runDueProcessesEveryDuePlanWithItsOwnTenant() {
        when(planRepo.findDueRefs(eq(today), anyInt())).thenReturn(java.util.Collections.singletonList(
            new Object[] {plan.getId().toString(), tenant.toString()}));
        PmGenerationService.Summary s = service.runDue(today);
        assertThat(s).isEqualTo(new PmGenerationService.Summary(1, 1, 0, 0));
        verify(planRepo).lockForGeneration(plan.getId(), tenant);
    }

    @Test
    void overdueRunIsNotifiedOnlyByTheFirstPass() {
        MaintenancePlanRun run = new MaintenancePlanRun();
        run.setId(UUID.randomUUID());
        run.setTenantId(tenant);
        run.setPlanId(plan.getId());
        run.setDueDate(today.minusDays(2));
        WorkOrder wo = new WorkOrder();
        wo.setId(UUID.randomUUID());
        wo.setTenantId(tenant);
        wo.setTicketNumber("T-1");
        run.setWorkOrderId(wo.getId());
        when(runRepo.findOverdueUnnotified(eq(today), anyInt())).thenReturn(List.of(run.getId()));
        when(runRepo.findById(run.getId())).thenReturn(Optional.of(run));
        when(workOrderRepo.findById(wo.getId())).thenReturn(Optional.of(wo));
        when(userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(center.getId(), UserRole.maintenance_manager))
            .thenReturn(List.of(owner));
        when(runRepo.markOverdueNotified(run.getId())).thenReturn(1, 0);

        assertThat(service.notifyOverdue(today)).isEqualTo(1);
        assertThat(service.notifyOverdue(today)).isZero();

        // the owner is also the center manager: told once, not twice
        verify(notifications, times(1)).notifyUser(eq(owner), eq(wo), eq("PM_OVERDUE"), contains("T-1"));
    }
}
