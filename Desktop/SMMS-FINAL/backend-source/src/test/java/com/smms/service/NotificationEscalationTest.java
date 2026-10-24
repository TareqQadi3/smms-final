package com.smms.service;

import com.smms.entity.*;
import com.smms.enums.TicketPriority;
import com.smms.enums.UserRole;
import com.smms.repository.NotificationRepository;
import com.smms.repository.UserControlRoomAccessRepository;
import com.smms.repository.UserRepository;
import com.smms.repository.WorkOrderRepository;
import com.smms.websocket.NotificationWebSocketHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** M3: approver and escalation recipients (center and tenant scoped), SLA scheduler, after-commit push. */
class NotificationEscalationTest {

    private NotificationRepository notifRepo;
    private NotificationWebSocketHandler ws;
    private UserRepository userRepo;
    private NotificationService service;
    private final UUID tenant = UUID.randomUUID();
    private Center center;

    @BeforeEach
    void setUp() {
        notifRepo = mock(NotificationRepository.class);
        ws = mock(NotificationWebSocketHandler.class);
        userRepo = mock(UserRepository.class);
        service = new NotificationService(notifRepo, mock(UserControlRoomAccessRepository.class), ws, userRepo);
        when(notifRepo.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));
        center = new Center();
        center.setId(UUID.randomUUID());
    }

    @AfterEach
    void cleanSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private User user(UserRole role, boolean active, UUID t) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        u.setActive(active);
        u.setTenantId(t);
        return u;
    }

    private WorkOrder ticket(TicketPriority p) {
        WorkOrder wo = new WorkOrder();
        wo.setId(UUID.randomUUID());
        wo.setTicketNumber("WO-9");
        wo.setPriority(p);
        wo.setCenter(center);
        wo.setTenantId(tenant);
        return wo;
    }

    @Test
    void approverNotificationReachesOnlyActiveSameTenantOpcoManagersOfTheCenter() {
        User ok = user(UserRole.operating_company_manager, true, tenant);
        when(userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(center.getId(), UserRole.operating_company_manager))
            .thenReturn(List.of(ok, user(UserRole.operating_company_manager, false, tenant),
                user(UserRole.operating_company_manager, true, UUID.randomUUID()),
                user(UserRole.operating_company_manager, true, null)));

        service.notifyApprover(ticket(TicketPriority.low));

        verify(notifRepo, times(1)).save(any(Notification.class));
        verify(ws).sendToUser(eq(ok.getId()), any(Notification.class));
    }

    @Test
    void escalationReachesManagersAndSupervisorsOfTheCenterOnly() {
        User mm = user(UserRole.maintenance_manager, true, tenant);
        User hs = user(UserRole.hospital_supervisor, true, tenant);
        when(userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(center.getId(), UserRole.maintenance_manager))
            .thenReturn(List.of(mm, user(UserRole.maintenance_manager, true, UUID.randomUUID())));
        when(userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(center.getId(), UserRole.hospital_supervisor))
            .thenReturn(List.of(hs));

        assertThat(service.notifyEscalation(ticket(TicketPriority.critical))).isEqualTo(2);

        verify(ws).sendToUser(eq(mm.getId()), any(Notification.class));
        verify(ws).sendToUser(eq(hs.getId()), any(Notification.class));
        verify(userRepo, never()).findAllByCenterIdAndRoleAndDeletedAtIsNull(any(), eq(UserRole.technician));
    }

    private com.smms.workflow.EscalationService scheduler(WorkOrderRepository repo, NotificationService ns,
                                                         com.smms.workflow.EscalationRepository esc) {
        when(esc.insertIgnore(any(), any(), any(), anyInt(), any(), any(), any())).thenReturn(1);
        when(repo.saveAndFlush(any(WorkOrder.class))).thenAnswer(i -> i.getArgument(0));
        return new com.smms.workflow.EscalationService(repo, esc, ns,
            mock(com.smms.security.WorkOrderAccessPolicy.class), mock(com.smms.workflow.WorkOrderHistoryWriter.class),
            new com.smms.workflow.EscalationProperties(),
            mock(org.springframework.transaction.PlatformTransactionManager.class));
    }

    @Test
    void schedulerEscalatesOnlyCriticalAndHigh() {
        WorkOrderRepository repo = mock(WorkOrderRepository.class);
        NotificationService ns = mock(NotificationService.class);
        com.smms.workflow.EscalationRepository esc = mock(com.smms.workflow.EscalationRepository.class);
        WorkOrder crit = ticket(TicketPriority.critical);
        WorkOrder high = ticket(TicketPriority.high);
        WorkOrder med = ticket(TicketPriority.medium);
        WorkOrder low = ticket(TicketPriority.low);
        List<WorkOrder> all = List.of(crit, high, med, low);
        all.forEach(w -> {
            w.setStatus(com.smms.enums.TicketStatus.IN_PROGRESS);
            w.setSlaDeadline(java.time.Instant.now().minusSeconds(60));
            when(repo.findById(w.getId())).thenReturn(java.util.Optional.of(w));
        });
        when(repo.findSlaBreached(any(), anyList())).thenReturn(all);

        scheduler(repo, ns, esc).runScheduledPass();

        // every breached ticket is flagged (entity update) and gets a level-1 row; only critical/high notify
        all.forEach(w -> assertThat(w.isSlaBreached()).isTrue());
        verify(esc, times(4)).insertIgnore(any(), any(), any(), eq(1), eq("sla_breach"), any(), any());
        verify(ns).notifyEscalation(crit, 1);
        verify(ns).notifyEscalation(high, 1);
        verify(ns, never()).notifyEscalation(eq(med), anyInt());
        verify(ns, never()).notifyEscalation(eq(low), anyInt());
    }

    @Test
    void schedulerDoesNothingWhenNothingBreached() {
        WorkOrderRepository repo = mock(WorkOrderRepository.class);
        NotificationService ns = mock(NotificationService.class);
        com.smms.workflow.EscalationRepository esc = mock(com.smms.workflow.EscalationRepository.class);
        when(repo.findSlaBreached(any(), anyList())).thenReturn(List.of());
        scheduler(repo, ns, esc).runScheduledPass();
        verifyNoInteractions(ns);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void socketPushWaitsForCommitAndNeverFiresOnRollback() {
        TransactionSynchronizationManager.initSynchronization();
        User target = user(UserRole.technician, true, tenant);

        service.notifyUser(target, ticket(TicketPriority.low), "ASSIGNED", "t");

        verify(notifRepo).save(any(Notification.class));
        verifyNoInteractions(ws); // inside the transaction: nothing pushed yet
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);
        syncs.forEach(TransactionSynchronization::afterCommit);
        verify(ws).sendToUser(eq(target.getId()), any(Notification.class));

        // rollback path: afterCommit is simply never invoked
        clearInvocations(ws);
        service.notifyUser(target, ticket(TicketPriority.low), "ASSIGNED", "t");
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(ws);
    }
}
