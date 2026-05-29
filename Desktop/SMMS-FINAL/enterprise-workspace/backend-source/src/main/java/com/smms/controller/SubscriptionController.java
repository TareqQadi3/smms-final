package com.smms.controller;

import com.smms.dto.request.CreateSubscriptionRequest;
import com.smms.dto.request.UpdateSubscriptionRequest;
import com.smms.dto.response.SubscriptionResponse;
import com.smms.entity.User;
import com.smms.service.SubscriptionService;
import com.smms.util.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    // ── Admin endpoints ─────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<List<SubscriptionResponse>> listAll() {
        return ResponseEntity.ok(subscriptionService.listAll());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<SubscriptionResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(subscriptionService.getById(id));
    }

    @GetMapping("/center/{centerId}")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<?> getByCenter(@PathVariable UUID centerId) {
        return subscriptionService.findByCenter(centerId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<SubscriptionResponse> create(
            @Valid @RequestBody CreateSubscriptionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(subscriptionService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<SubscriptionResponse> update(
            @PathVariable UUID id,
            @RequestBody UpdateSubscriptionRequest req) {
        return ResponseEntity.ok(subscriptionService.update(id, req));
    }

    @PostMapping("/{id}/renew")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<SubscriptionResponse> renew(
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate newEndDate,
            @RequestParam(required = false) String notes) {
        return ResponseEntity.ok(subscriptionService.renew(id, newEndDate, notes));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        subscriptionService.revoke(id);
        return ResponseEntity.noContent().build();
    }

    // ── Self-service endpoint (any authenticated user) ──────

    /** Returns the subscription status for the current user's center */
    @GetMapping("/my")
    public ResponseEntity<?> mySubscription(@CurrentUser User currentUser) {
        if (currentUser.getCenter() == null) {
            return ResponseEntity.ok(Map.of("message", "No center assigned"));
        }
        return subscriptionService.findByCenter(currentUser.getCenter().getId())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.ok(null));
    }
}
