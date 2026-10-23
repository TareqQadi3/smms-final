package com.smms.controller;

import com.smms.dto.request.TenantAssignmentRequest;
import com.smms.dto.response.TenantAssignmentCandidateResponse;
import com.smms.dto.response.TenantAssignmentResponse;
import com.smms.entity.User;
import com.smms.exception.GlobalExceptionHandler.ErrorBody;
import com.smms.service.TenantAssignmentService;
import com.smms.service.TenantAssignmentService.TenantAlreadyAssignedException;
import com.smms.util.CurrentUser;
import com.smms.util.PageRequests;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Platform-admin only: report and repair users that have no tenant. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class TenantAssignmentController {

    private static final Map<String, String> SORT_FIELDS = Map.of(
        "username", "username", "createdAt", "createdAt");

    private final TenantAssignmentService service;

    @GetMapping("/tenantless-users")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<Page<TenantAssignmentCandidateResponse>> tenantless(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) List<String> sort,
            @CurrentUser User actor) {
        Pageable pageable = PageRequests.of(page, size, sort, SORT_FIELDS, Sort.by("createdAt").descending());
        return ResponseEntity.ok(service.listTenantless(actor, pageable));
    }

    @PostMapping("/users/{id}/tenant")
    @PreAuthorize("hasRole('system_admin')")
    public ResponseEntity<TenantAssignmentResponse> assign(@PathVariable UUID id,
                                                           @Valid @RequestBody TenantAssignmentRequest req,
                                                           @CurrentUser User actor) {
        return ResponseEntity.ok(service.assign(id, req.getTenantId(), actor));
    }

    /** Local mapping: the shared advice has no 409 for this case and is owned by another workstream. */
    @ExceptionHandler(TenantAlreadyAssignedException.class)
    public ResponseEntity<ErrorBody> alreadyAssigned(TenantAlreadyAssignedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErrorBody(HttpStatus.CONFLICT.value(), e.getMessage(), Instant.now()));
    }
}
