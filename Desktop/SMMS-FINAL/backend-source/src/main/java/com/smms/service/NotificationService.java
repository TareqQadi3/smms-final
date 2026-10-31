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
        switch (wo.getStatus()) {
            // technician needs materials: the warehouse of the center must learn about it
            case WAITING_MATERIALS -> notifyWarehouse(wo, actor);
            // work finished: control room operators (to review/close) and the requester (to confirm)
            case COMPLETED -> {
                notifyRoomOperatorsExcept(wo, actor, "WORK_COMPLETED", "Work completed: " + wo.getTicketNumber());
                notifyRequester(wo, actor, "WORK_COMPLETED", "Work completed, please confirm: " + wo.getTicketNumber());
            }
            case CANCELLED -> notifyRequester(wo, actor, "CANCELLED", "Request cancelled: " + wo.getTicketNumber());
            default -> { }
        }
    }

    /** Active warehouse users of the ticket's center and tenant are told that materials are needed. */
    public int notifyWarehouse(WorkOrder wo, User actor) {
        return notifyCenterRolesExcept(wo, List.of(UserRole.warehouse), actor, "MATERIALS_REQUIRED",
            "Materials required: " + wo.getTicketNumber());
    }

    /** The warehouse supplied the materials: the assigned technician can continue. */
    public void notifyMaterialsProvided(WorkOrder wo, User actor) {
        notifyAssignee(wo, actor, "MATERIALS_PROVIDED", "Materials provided: " + wo.getTicketNumber());
    }

    /** Approval granted (approved or purchase_request): the assigned technician can continue. */
    public void notifyApprovalGranted(WorkOrder wo, User actor) {
        notifyAssignee(wo, actor, "APPROVAL_GRANTED", "Approval granted: " + wo.getTicketNumber());
    }

    /** The ticket was closed: tell the requester. */
    public void notifyClosed(WorkOrder wo, User actor) {
        notifyRequester(wo, actor, "CLOSED", "Request closed: " + wo.getTicketNumber());
    }

    /** A completed ticket was reopened: the assigned technician has to redo the work. */
    public void notifyReopened(WorkOrder wo, User actor) {
        notifyAssignee(wo, actor, "REOPENED", "Ticket reopened: " + wo.getTicketNumber());
    }

    /** The requester accepted or disputed the completed work: tell the control room operators. */
    public void notifyRequesterVerdict(WorkOrder wo, User actor, boolean accepted) {
        notifyRoomOperatorsExcept(wo, actor, accepted ? "REQUESTER_ACCEPTED" : "REQUESTER_DISPUTED",
            (accepted ? "Requester accepted the work: " : "Requester disputed the work: ") + wo.getTicketNumber());
        if (!accepted) notifyAssignee(wo, actor, "REQUESTER_DISPUTED",
            "Requester disputed the work: " + wo.getTicketNumber());
    }

    public void notifyRequester(WorkOrder wo, User actor, String type, String title) {
        User r = wo.getRequestedBy();
        if (r == null || (actor != null && r.getId().equals(actor.getId()))) return;
        sendAndPush(r, wo, type, title);
    }

    private void notifyAssignee(WorkOrder wo, User actor, String type, String title) {
        User a = wo.getAssignedTo();
        if (a == null || (actor != null && a.getId().equals(actor.getId()))) return;
        sendAndPush(a, wo, type, title);
    }

    private void notifyRoomOperatorsExcept(WorkOrder wo, User actor, String type, String title) {
        if (wo.getControlRoom() == null) return;
        accessRepo.findByControlRoomId(wo.getControlRoom().getId()).stream()
            .map(UserControlRoomAccess::getUser)
            .filter(u -> u.getRole() == UserRole.control_room && u.isActive()
                && java.util.Objects.equals(u.getTenantId(), wo.getTenantId())
                && (actor == null || !u.getId().equals(actor.getId())))
            .distinct()
            .forEach(u -> sendAndPush(u, wo, type, title));
    }

    /**
     * A ticket entered waiting_approval: tell the active operating_company_manager users of its center
     * and tenant. When the center has none, fall back to its maintenance managers (WARN) so the
     * request never disappears silently.
     */
    public void notifyApprover(WorkOrder wo) {
        int n = notifyCenterRoles(wo, List.of(UserRole.operating_company_manager), "APPROVAL_REQUIRED",
            "Approval required: " + wo.getTicketNumber());
        if (n == 0) {
            log.warn("No active operating_company_manager in center {} for workOrder {}: falling back to maintenance managers",
                wo.getCenter() != null ? wo.getCenter().getId() : null, wo.getId());
            n = notifyCenterRoles(wo, List.of(UserRole.maintenance_manager), "APPROVAL_REQUIRED",
                "Approval required (no approver configured): " + wo.getTicketNumber());
        }
        log.info("AUDIT approval requested: workOrder={} notified={}", wo.getId(), n);
    }

    /**
     * Escalation notification by level. Level 1: maintenance managers and hospital supervisors of the
     * center. Levels 2 and 3: the region supervisors of the center's region plus the operating
     * company managers of the center. Returns the number of notifications created.
     */
    public int notifyEscalation(WorkOrder wo, int level) {
        if (level <= 1) return notifyEscalation(wo);
        int n = notifyCenterRoles(wo, List.of(UserRole.operating_company_manager), "ESCALATION",
            "Escalation level " + level + ": " + wo.getTicketNumber());
        Center c = wo.getCenter();
        Region region = c != null ? c.getRegion() : null;
        if (region != null) {
            UUID regionId = region.getId();
            List<User> supervisors = userRepo.findAll((root, q, cb) -> cb.and(
                cb.equal(root.get("role"), UserRole.region_supervisor),
                cb.equal(root.get("region").get("id"), regionId),
                cb.isNull(root.get("deletedAt")),
                cb.equal(root.get("tenantId"), wo.getTenantId())));
            for (User u : supervisors) {
                if (!u.isActive()) continue;
                sendAndPush(u, wo, "ESCALATION", "Escalation level " + level + ": " + wo.getTicketNumber());
                n++;
            }
        }
        log.info("AUDIT sla escalation: workOrder={} level={} notified={}", wo.getId(), level, n);
        return n;
    }

    /** Emergency declared: managers and supervisors of the center and the control room operators. */
    public int notifyEmergency(WorkOrder wo, User actor) {
        String title = "EMERGENCY: " + wo.getTicketNumber();
        int n = notifyCenterRolesExcept(wo, List.of(UserRole.maintenance_manager, UserRole.hospital_supervisor),
            actor, "EMERGENCY", title);
        if (wo.getControlRoom() != null) {
            List<User> ops = accessRepo.findByControlRoomId(wo.getControlRoom().getId()).stream()
                .map(UserControlRoomAccess::getUser)
                .filter(u -> u.getRole() == UserRole.control_room && u.isActive()
                    && java.util.Objects.equals(u.getTenantId(), wo.getTenantId())
                    && (actor == null || !u.getId().equals(actor.getId())))
                .distinct().toList();
            for (User u : ops) { sendAndPush(u, wo, "EMERGENCY", title); n++; }
        }
        return n;
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

    private int notifyCenterRolesExcept(WorkOrder wo, List<UserRole> roles, User actor, String type, String title) {
        if (wo.getCenter() == null) return 0;
        int sent = 0;
        for (UserRole role : roles) {
            for (User u : userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(wo.getCenter().getId(), role)) {
                if (!u.isActive() || !java.util.Objects.equals(u.getTenantId(), wo.getTenantId())) continue;
                if (actor != null && u.getId().equals(actor.getId())) continue;
                sendAndPush(u, wo, type, title);
                sent++;
            }
        }
        return sent;
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
