package com.smms.service;

import com.smms.dto.response.NotificationResponse;
import com.smms.entity.*;
import com.smms.repository.NotificationRepository;
import com.smms.repository.UserControlRoomAccessRepository;
import com.smms.websocket.NotificationWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    private final NotificationRepository notifRepo;
    private final UserControlRoomAccessRepository accessRepo;
    private final NotificationWebSocketHandler wsHandler;

    public void notifyControlRoom(WorkOrder wo) {
        List<UserControlRoomAccess> members =
            accessRepo.findByControlRoomId(wo.getControlRoom().getId());
        members.forEach(m -> sendAndPush(m.getUser(), wo, "NEW_TICKET",
            "تذكرة جديدة: " + wo.getTicketNumber()));
    }

    public void notifyTransition(WorkOrder wo, User actor) {
        if (wo.getAssignedTo() != null && !wo.getAssignedTo().getId().equals(actor.getId())) {
            sendAndPush(wo.getAssignedTo(), wo, "STATUS_CHANGE",
                "تحديث التذكرة: " + wo.getTicketNumber() + " → " + wo.getStatus());
        }
    }

    public void notifyApprover(WorkOrder wo) {
        // In real system: query operating_company_manager users for this center
    }

    public void notifyUser(User user, WorkOrder wo, String type, String title) {
        if (user == null) return;
        sendAndPush(user, wo, type, title);
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(UUID userId, int page, int size) {
        return notifRepo.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
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
        wsHandler.sendToUser(user.getId(), n);
    }
}
