package com.smms.service;

import com.smms.entity.ControlRoom;
import com.smms.entity.Notification;
import com.smms.entity.User;
import com.smms.entity.UserControlRoomAccess;
import com.smms.entity.WorkOrder;
import com.smms.enums.UserRole;
import com.smms.repository.NotificationRepository;
import com.smms.repository.UserControlRoomAccessRepository;
import com.smms.websocket.NotificationWebSocketHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    private static User user(UserRole role, boolean active) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        u.setActive(active);
        return u;
    }

    private static UserControlRoomAccess member(User u) {
        UserControlRoomAccess a = new UserControlRoomAccess();
        a.setUser(u);
        return a;
    }

    @Test
    void newTicketOnlyReachesActiveControlRoomOperators() {
        NotificationRepository notifRepo = mock(NotificationRepository.class);
        UserControlRoomAccessRepository accessRepo = mock(UserControlRoomAccessRepository.class);
        NotificationWebSocketHandler ws = mock(NotificationWebSocketHandler.class);
        NotificationService service = new NotificationService(notifRepo, accessRepo, ws);

        User operator = user(UserRole.control_room, true);
        ControlRoom cr = new ControlRoom();
        cr.setId(UUID.randomUUID());
        WorkOrder wo = new WorkOrder();
        wo.setControlRoom(cr);
        when(accessRepo.findByControlRoomId(cr.getId())).thenReturn(List.of(
            member(operator),
            member(user(UserRole.resident, true)),
            member(user(UserRole.department, true)),
            member(user(UserRole.other, true)),
            member(user(UserRole.technician, true)),
            member(user(UserRole.control_room, false))));
        when(notifRepo.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

        service.notifyControlRoom(wo);

        verify(notifRepo, times(1)).save(any(Notification.class));
        verify(ws, times(1)).sendToUser(eq(operator.getId()), any(Notification.class));
        verify(ws, times(1)).sendToUser(any(UUID.class), any(Notification.class));
    }

    @Test
    void listUsesCallerPageableAndOnlyTheCallersNotifications() {
        NotificationRepository notifRepo = mock(NotificationRepository.class);
        NotificationService service = new NotificationService(notifRepo,
            mock(UserControlRoomAccessRepository.class), mock(NotificationWebSocketHandler.class));
        UUID userId = UUID.randomUUID();
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(
            1, 10, org.springframework.data.domain.Sort.by("createdAt").descending());
        when(notifRepo.findByUserId(userId, pageable)).thenReturn(org.springframework.data.domain.Page.empty(pageable));

        service.list(userId, pageable);

        verify(notifRepo).findByUserId(userId, pageable);
    }
}
