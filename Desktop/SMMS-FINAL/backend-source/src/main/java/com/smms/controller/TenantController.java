package com.smms.controller;

import com.smms.config.PlanConfig;
import com.smms.entity.Tenant;
import com.smms.entity.TenantSubscription;
import com.smms.enums.PlanType;
import com.smms.service.PlanEnforcementService;
import com.smms.service.SubscriptionService;
import com.smms.service.TenantService;
import com.smms.dto.request.CreateTenantRequest;
import com.smms.dto.response.CreateTenantResponse;
import com.smms.dto.response.TenantResponse;
import com.smms.dto.response.TenantSubscriptionResponse;
import com.smms.mapper.TenantMapper;
import com.smms.util.PageRequests;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * Platform-level tenant management — system_admin only.
 * All endpoints under /api/platform/tenants
 */
@Slf4j
@RestController
@RequestMapping("/api/platform/tenants")
@RequiredArgsConstructor
@PreAuthorize("hasRole('system_admin')")
public class TenantController {

    private static final Map<String, String> SORT_FIELDS = Map.of("createdAt", "createdAt", "name", "name");

    private final TenantService          tenantService;
    private final SubscriptionService    subscriptionService;
    private final PlanEnforcementService enforcement;

    // ── Tenant CRUD ───────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Page<TenantResponse>> listAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) List<String> sort) {
        Pageable pageable = PageRequests.of(page, size, sort, SORT_FIELDS, Sort.by(Sort.Direction.ASC, "name"));
        return ResponseEntity.ok(tenantService.list(pageable).map(TenantMapper::toResponse));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TenantResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(TenantMapper.toResponse(tenantService.getById(id)));
    }

    @PostMapping
    public ResponseEntity<CreateTenantResponse> create(@Valid @RequestBody CreateTenantRequest req) {
        Tenant tenant = tenantService.create(req.getName(), req.getNameAr(), req.getSlug());
        TenantSubscription sub = subscriptionService.createDefault(tenant.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(
            new CreateTenantResponse(TenantMapper.toResponse(tenant), TenantMapper.toResponse(sub)));
    }

    @PutMapping("/{id}/active")
    public ResponseEntity<TenantResponse> toggleActive(@PathVariable UUID id,
                                                       @RequestParam boolean active) {
        return ResponseEntity.ok(TenantMapper.toResponse(tenantService.toggleActive(id, active)));
    }

    // ── Subscription Management ───────────────────────────────

    @GetMapping("/{id}/subscription")
    public ResponseEntity<TenantSubscriptionResponse> getSubscription(@PathVariable UUID id) {
        return ResponseEntity.ok(TenantMapper.toResponse(subscriptionService.get(id)));
    }

    /** Suspend a tenant — they get read-only access. */
    @PostMapping("/{id}/suspend")
    public ResponseEntity<TenantSubscriptionResponse> suspend(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "Suspended by admin") String reason) {
        return ResponseEntity.ok(TenantMapper.toResponse(subscriptionService.suspend(id, reason)));
    }

    /** Re-activate a suspended or expired tenant. */
    @PostMapping("/{id}/activate")
    public ResponseEntity<TenantSubscriptionResponse> activate(@PathVariable UUID id) {
        return ResponseEntity.ok(TenantMapper.toResponse(subscriptionService.activate(id)));
    }

    /** Change subscription plan. durationDays=null means lifetime (ENTERPRISE). */
    @PutMapping("/{id}/plan")
    public ResponseEntity<TenantSubscriptionResponse> changePlan(
            @PathVariable UUID id,
            @RequestParam PlanType planType,
            @RequestParam(required = false) Integer durationDays) {
        return ResponseEntity.ok(TenantMapper.toResponse(subscriptionService.changePlan(id, planType, durationDays)));
    }

    // ── Usage Statistics ──────────────────────────────────────

    @GetMapping("/{id}/usage")
    public ResponseEntity<Map<String, Object>> usage(@PathVariable UUID id) {
        TenantSubscription sub = subscriptionService.get(id);
        PlanEnforcementService.UsageMetrics metrics = enforcement.getUsage(id);
        PlanConfig.PlanLimits limits = PlanConfig.get(sub.getPlanType());

        return ResponseEntity.ok(Map.of(
            "tenantId",   id,
            "plan",       sub.getPlanType(),
            "status",     sub.getStatus(),
            "endDate",    sub.getEndDate() != null ? sub.getEndDate().toString() : "lifetime",
            "usage", Map.of(
                "users",   Map.of("current", metrics.users(),   "max", limits.isUnlimited() ? "∞" : limits.maxUsers()),
                "tickets", Map.of("current", metrics.tickets(), "max", limits.isUnlimited() ? "∞" : limits.maxTickets()),
                "centers", Map.of("current", metrics.centers(), "max", limits.isUnlimited() ? "∞" : limits.maxCenters())
            )
        ));
    }

    /** Usage summary across ALL tenants — platform analytics. */
    @GetMapping("/analytics")
    public ResponseEntity<List<Map<String, Object>>> allUsage() {
        return ResponseEntity.ok(
            tenantService.listAll().stream().map(t -> {
                try {
                    TenantSubscription sub = subscriptionService.get(t.getId());
                    PlanEnforcementService.UsageMetrics m = enforcement.getUsage(t.getId());
                    return Map.<String, Object>of(
                        "tenantId",   t.getId(),
                        "tenantName", t.getName(),
                        "plan",       sub.getPlanType(),
                        "status",     sub.getStatus(),
                        "users",      m.users(),
                        "tickets",    m.tickets(),
                        "centers",    m.centers()
                    );
                } catch (Exception e) {
                    // Never return exception text to the client; details stay in the server log
                    log.warn("Usage analytics failed for tenant {}: {}", t.getId(), e.getMessage());
                    return Map.<String, Object>of("tenantId", t.getId(), "error", "unavailable");
                }
            }).toList()
        );
    }
}
