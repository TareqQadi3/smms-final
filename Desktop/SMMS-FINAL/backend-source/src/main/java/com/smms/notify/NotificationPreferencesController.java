package com.smms.notify;

import com.smms.entity.User;
import com.smms.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The caller's own external-channel preferences. In-app notifications are always on. */
@RestController
@RequestMapping("/api/notifications/preferences")
@RequiredArgsConstructor
public class NotificationPreferencesController {

    public record UpdateBody(boolean emailEnabled, boolean smsEnabled, List<String> types) {}

    private final NotificationPreferencesService service;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<NotificationPreferencesService.Preferences> get(@CurrentUser User actor) {
        return ResponseEntity.ok(service.get(actor));
    }

    @PutMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<NotificationPreferencesService.Preferences> put(@CurrentUser User actor,
                                                                           @RequestBody UpdateBody body) {
        return ResponseEntity.ok(service.update(actor,
            new NotificationPreferencesService.Update(body.emailEnabled(), body.smsEnabled(), body.types())));
    }
}
