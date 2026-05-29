package com.smms.controller;

import com.smms.config.PlanConfig;
import com.smms.entity.Tenant;
import com.smms.entity.TenantSubscription;
import com.smms.enums.PlanType;
import com.smms.service.PlanEnforcementService;
import com.smms.service.SubscriptionService;
import com.smms.service.TenantService;
import lombok.RequiredArgsConstructor;
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
@RestController
@RequestMapping("/api/platform/tenants")
@RequiredArgsConstructor
@PreAuthorize("hasRole('system_admin')")
public class TenantController {

    private final TenantService          tenantService;
    private final SubscriptionService    subscriptionService;
    private final PlanEnforcementService enforcement;

    // ── Tenant CRUD ───────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<Tenant>> listAll() {
        return ResponseEntity.ok(tenantService.listAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Tenant> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(tenantService.getById(id));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        Tenant tenant = tenantService.create(
            body.get("name"), body.get("nameAr"), body.get("slug"));
        TenantSubscription sub = subscriptionService.createDefault(tenant.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("tenant", tenant, "subscription", sub));
    }

    @PutMapping("/{id}/active")
    public ResponseEntity<Tenant> toggleActive(@PathVariable UUID id,
                                                @RequestParam boolean active) {
        return ResponseEntity.ok(tenantService.toggleActive(id, active));
    }

    // ── Subscription Management ───────────────────────────────

    @GetMapping("/{id}/subscription")
    public ResponseEntity<TenantSubscription> getSubscription(@PathVariable UUID id) {
        return ResponseEntity.ok(subscriptionService.get(id));
    }

    /** Suspend a tenant — they get read-only access. */
    @PostMapping("/{id}/suspend")
    public ResponseEntity<TenantSubscription> suspend(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "Suspended by admin") String reason) {
        return ResponseEntity.ok(subscriptionService.suspend(id, reason));
    }

    /** Re-activate a suspended or expired tenant. */
    @PostMapping("/{id}/activate")
    public ResponseEntity<TenantSubscription> activate(@PathVariable UUID id) {
        return ResponseEntity.ok(subscriptionService.activate(id));
    }

    /** Change subscription plan. durationDays=null means lifetime (ENTERPRISE). */
    @PutMapping("/{id}/plan")
    public ResponseEntity<TenantSubscription> changePlan(
            @PathVariable UUID id,
            @RequestParam PlanType planType,
            @RequestParam(required = false) Integer durationDays) {
        return ResponseEntity.ok(subscriptionService.changePlan(id, planType, durationDays));
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
                    return Map.<String, Object>of("tenantId", t.getId(), "error", e.getMessage());
                }
            }).toList()
        );
    }
}
