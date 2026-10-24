package com.smms.notify;

import com.smms.dto.response.NotificationResponse;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** In-app inbox reads and per-id read marking. Independent of every external provider. */
@Service
@RequiredArgsConstructor
public class NotificationInboxService {

    private final NotificationRepository repo;

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(UUID userId, Pageable pageable) {
        return repo.findByUserId(userId, pageable).map(n -> NotificationResponse.builder()
            .id(n.getId())
            .type(n.getType())
            .title(n.getTitle())
            .body(n.getBody())
            .workOrderId(n.getWorkOrder() != null ? n.getWorkOrder().getId() : null)
            .read(n.isRead())
            .createdAt(n.getCreatedAt())
            .build());
    }

    /** Idempotent for the owner (already read is fine); someone else's or an unknown id is a 404. */
    @Transactional
    public void markRead(UUID userId, UUID id) {
        if (repo.countByIdAndUser(id, userId) == 0) throw new ResourceNotFoundException("Notification not found: " + id);
        repo.markReadByIdAndUser(id, userId);
    }
}
