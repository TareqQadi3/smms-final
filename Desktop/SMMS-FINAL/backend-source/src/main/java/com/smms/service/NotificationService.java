package com.smms.service;

import com.smms.dto.response.NotificationResponse;
import com.smms.entity.*;
import com.smms.enums.UserRole;
import com.smms.repository.NotificationRepository;
import com.smms.repository.UserControlRoomAccessRepository;
import com.smms.repository.UserRepository;
import com.smms.websocket.NotificationWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    private final NotificationRepository notifRepo;
    private final UserControlRoomAccessRepository accessRepo;
    private final NotificationWebSocketHandler wsHandler;
    private final UserRepository userRepo;

    public void notifyControlRoom(WorkOrder wo) {
        notifyRoomOperators(wo, "NEW_TICKET", "تذكرة جديدة: " + wo.getTicketNumber());
    }

    /** The requester answered an information request: tell the room's operators. */
    public void notifyInfoProvided(WorkOrder wo) {
        notifyRoomOperators(wo, "INFO_PROVIDED", "Information provided: " + wo.getTicketNumber());
    }

    private void notifyRoomOperators(WorkOrder wo, String type, String title) {
        List<UserControlRoomAccess> members =
            accessRepo.findByControlRoomId(wo.getControlRoom().getId());
        // Only active control-room operators are told about tickets; residents, departments and
        // other roles can hold access rows to the room but must not learn about others' tickets
        members.stream()
            .map(UserControlRoomAccess::getUser)
            .filter(u -> u.getRole() == UserRole.control_room && u.isActive())
            .forEach(u -> sendAndPush(u, wo, type, title));
    }

    public void notifyTransition(WorkOrder wo, User actor) {
        if (wo.getAssignedTo() != null && !wo.getAssignedTo().getId().equals(actor.getId())) {
            sendAndPush(wo.getAssignedTo(), wo, "STATUS_CHANGE",
                "تحديث التذكرة: " + wo.getTicketNumber() + " → " + wo.getStatus());
        }
    }

    /** A ticket entered waiting_approval: tell the active operating_company_manager users of its center and tenant. */
    public void notifyApprover(WorkOrder wo) {
        int n = notifyCenterRoles(wo, List.of(UserRole.operating_company_manager), "APPROVAL_REQUIRED",
            "Approval required: " + wo.getTicketNumber());
        log.info("AUDIT approval requested: workOrder={} notified={}", wo.getId(), n);
    }

    /**
     * SLA breach on a critical/high ticket: notification-only escalation to the active
     * maintenance_manager and hospital_supervisor users of the ticket's center and tenant.
     * No ticket_history row (actor is NOT NULL and there is no system user); the evidence is the
     * notification rows plus this log line.
     */
    public int notifyEscalation(WorkOrder wo) {
        int n = notifyCenterRoles(wo, List.of(UserRole.maintenance_manager, UserRole.hospital_supervisor),
            "ESCALATION", "SLA breached (" + wo.getPriority() + "): " + wo.getTicketNumber());
        log.info("AUDIT sla escalation: workOrder={} priority={} notified={}", wo.getId(), wo.getPriority(), n);
        return n;
    }

    private int notifyCenterRoles(WorkOrder wo, List<UserRole> roles, String type, String title) {
        if (wo.getCenter() == null) return 0;
        int sent = 0;
        for (UserRole role : roles) {
            for (User u : userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(wo.getCenter().getId(), role)) {
                // Same tenant as the ticket and active: never cross a tenant or notify disabled accounts
                if (!u.isActive() || !java.util.Objects.equals(u.getTenantId(), wo.getTenantId())) continue;
                sendAndPush(u, wo, type, title);
                sent++;
            }
        }
        return sent;
    }

    public void notifyUser(User user, WorkOrder wo, String type, String title) {
        if (user == null) return;
        sendAndPush(user, wo, type, title);
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(UUID userId, Pageable pageable) {
        return notifRepo.findByUserId(userId, pageable)
            .map(n -> NotificationResponse.builder()
                .id(n.getId())
                .type(n.getType())
                .title(n.getTitle())
                .body(n.getBody())
                .read(n.isRead())
                .createdAt(n.getCreatedAt())
                .build());
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notifRepo.countByUserIdAndReadFalse(userId);
    }

    public void markAllRead(UUID userId) {
        notifRepo.markAllReadByUser(userId);
    }

    private void sendAndPush(User user, WorkOrder wo, String type, String title) {
        Notification n = notifRepo.save(Notification.builder()
            .user(user).workOrder(wo).type(type).title(title).build());
        // Push only after the surrounding transaction commits, so a rolled-back operation never
        // produces a frame for something that did not happen
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { wsHandler.sendToUser(user.getId(), n); }
            });
        } else {
            wsHandler.sendToUser(user.getId(), n);
        }
    }
}
