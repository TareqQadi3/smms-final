package com.smms.controller;

import com.smms.dto.response.NotificationResponse;
import com.smms.entity.User;
import com.smms.notify.NotificationInboxService;
import com.smms.service.NotificationService;
import com.smms.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import com.smms.util.PageRequests;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private static final Map<String, String> SORT_FIELDS = Map.of("createdAt", "createdAt");

    private final NotificationService notificationService;
    private final NotificationInboxService inbox;

    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> list(
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(required = false) List<String> sort,
                                  @CurrentUser User actor) {
        Pageable pageable = PageRequests.of(page, size, sort, SORT_FIELDS,
            Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(inbox.list(actor.getId(), pageable));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Long> unreadCount(@CurrentUser User actor) {
        return ResponseEntity.ok(notificationService.unreadCount(actor.getId()));
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> readAll(@CurrentUser User actor) {
        notificationService.markAllRead(actor.getId());
        return ResponseEntity.noContent().build();
    }

    /** Marks one of the caller's own notifications read; another user's id is a 404. */
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable java.util.UUID id, @CurrentUser User actor) {
        inbox.markRead(actor.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
