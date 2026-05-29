package com.smms.controller;

import com.smms.entity.User;
import com.smms.service.NotificationService;
import com.smms.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @CurrentUser User actor) {
        return ResponseEntity.ok(notificationService.list(actor.getId(), page, size));
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
}
