package com.smms.procurement.service;

import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.UserRole;
import com.smms.repository.UserRepository;
import com.smms.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** In-app notifications for procurement and material requests (best effort: never fails the operation). */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcurementNotifier {

    private final UserRepository userRepo;
    private final NotificationService notifications;

    /** Active users of the center (same tenant) holding one of the roles, except {@code exclude}. */
    public int toRoles(UUID tenantId, UUID centerId, Set<UserRole> roles, UUID exclude, WorkOrder wo,
                       String type, String title) {
        int n = 0;
        for (UserRole role : roles) {
            for (User u : userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(centerId, role)) {
                if (!u.isActive() || !Objects.equals(u.getTenantId(), tenantId)) continue;
                if (exclude != null && exclude.equals(u.getId())) continue;
                if (send(u, wo, type, title)) n++;
            }
        }
        return n;
    }

    public boolean toUser(User user, WorkOrder wo, String type, String title) {
        return user != null && user.isActive() && send(user, wo, type, title);
    }

    private boolean send(User u, WorkOrder wo, String type, String title) {
        try {
            notifications.notifyUser(u, wo, type, title);
            return true;
        } catch (RuntimeException e) {
            log.warn("Notification {} to user {} failed: {}", type, u.getId(), e.getClass().getSimpleName());
            return false;
        }
    }
}
