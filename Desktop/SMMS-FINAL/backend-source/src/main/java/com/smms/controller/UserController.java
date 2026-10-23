package com.smms.controller;

import com.smms.dto.request.CreateUserRequest;
import com.smms.dto.request.ResetPasswordRequest;
import com.smms.dto.request.UpdateUserRequest;
import com.smms.dto.response.UserResponse;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.service.UserService;
import com.smms.util.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.smms.util.PageRequests;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class UserController {

    private static final Map<String, String> SORT_FIELDS = Map.of(
        "createdAt", "createdAt", "username", "username", "fullName", "fullName", "role", "role");

    private final UserService userService;

    // ── Create User ──────────────────────────────────────────
    @PostMapping({"/api/users", "/api/admin/users"})
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor','hospital_supervisor')")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest req,
                                               @CurrentUser User actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(req, actor));
    }

    // ── List Users ───────────────────────────────────────────
    @GetMapping({"/api/users", "/api/admin/users"})
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor','hospital_supervisor')")
    public ResponseEntity<Page<UserResponse>> list(
        @RequestParam(required = false) UUID centerId,
        @RequestParam(required = false) UserRole role,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) List<String> sort,
        @CurrentUser User actor) {
        Pageable pageable = PageRequests.of(page, size, sort, SORT_FIELDS,
            Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(userService.list(actor, centerId, role, pageable));
    }

    // ── Update User ──────────────────────────────────────────
    @PutMapping({"/api/users/{id}", "/api/admin/users/{id}"})
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor','hospital_supervisor')")
    public ResponseEntity<UserResponse> update(@PathVariable UUID id,
                                               @Valid @RequestBody UpdateUserRequest req,
                                               @CurrentUser User actor) {
        return ResponseEntity.ok(userService.update(id, req, actor));
    }

    // ── Toggle Active ────────────────────────────────────────
    @PutMapping({"/api/users/{id}/active", "/api/admin/users/{id}/active"})
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor','hospital_supervisor')")
    public ResponseEntity<Void> toggleActive(@PathVariable UUID id,
                                             @RequestParam boolean active,
                                             @CurrentUser User actor) {
        userService.toggleActive(id, active, actor);
        return ResponseEntity.noContent().build();
    }

    // ── My Profile ──────────────────────────────────────────
    @GetMapping("/api/users/me")
    public ResponseEntity<UserResponse> me(@CurrentUser User actor) {
        return ResponseEntity.ok(userService.getById(actor.getId()));
    }

    @PutMapping("/api/users/me")
    public ResponseEntity<UserResponse> updateMe(@Valid @RequestBody UpdateUserRequest req,
                                                  @CurrentUser User actor) {
        return ResponseEntity.ok(userService.updateMe(actor.getId(), req));
    }

    @PostMapping("/api/users/me/change-password")
    public ResponseEntity<Void> changePassword(@RequestBody ChangePasswordRequest req,
                                                @CurrentUser User actor) {
        userService.changePassword(actor.getId(), req.getCurrentPassword(), req.getNewPassword());
        return ResponseEntity.noContent().build();
    }

    // ── Admin Password Reset ─────────────────────────────────
    @PostMapping({"/api/users/{id}/reset-password", "/api/admin/users/{id}/reset-password"})
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id,
                                               @Valid @RequestBody ResetPasswordRequest req) {
        userService.resetPassword(id, req.getNewPassword());
        return ResponseEntity.noContent().build();
    }

    // ── Grant Control Room Access ────────────────────────────
    @PostMapping({"/api/users/{id}/control-rooms", "/api/admin/users/{id}/control-rooms"})
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor','hospital_supervisor')")
    public ResponseEntity<Void> grantAccess(@PathVariable UUID id,
                                             @RequestParam UUID controlRoomId,
                                             @CurrentUser User actor) {
        userService.grantControlRoomAccess(id, controlRoomId, actor);
        return ResponseEntity.noContent().build();
    }
}
