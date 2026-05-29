package com.smms.service;

import com.smms.dto.request.CreateSubscriptionRequest;
import com.smms.dto.request.UpdateSubscriptionRequest;
import com.smms.dto.response.SubscriptionResponse;
import com.smms.entity.Center;
import com.smms.entity.Subscription;
import com.smms.enums.SubscriptionPlan;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.CenterRepository;
import com.smms.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepo;
    private final CenterRepository centerRepo;

    @Transactional(readOnly = true)
    public List<SubscriptionResponse> listAll() {
        return subscriptionRepo.findAllByDeletedAtIsNull()
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Optional<SubscriptionResponse> findByCenter(UUID centerId) {
        return subscriptionRepo.findByCenterIdAndDeletedAtIsNull(centerId)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse getById(UUID id) {
        return toResponse(findOrThrow(id));
    }

    /** Creates a new subscription for a center. Throws if one already exists. */
    public SubscriptionResponse create(CreateSubscriptionRequest req) {
        if (subscriptionRepo.existsByCenterIdAndDeletedAtIsNull(req.getCenterId())) {
            throw new IllegalArgumentException("Center already has an active subscription. Use update to modify it.");
        }

        Center center = centerRepo.findById(req.getCenterId())
                .orElseThrow(() -> new ResourceNotFoundException("Center not found"));

        SubscriptionPlan plan = req.getPlan();
        LocalDate start = req.getStartDate() != null ? req.getStartDate() : LocalDate.now();
        LocalDate end   = resolveEndDate(req.getEndDate(), start, plan);

        Subscription sub = Subscription.builder()
                .center(center)
                .plan(plan)
                .startDate(start)
                .endDate(end)
                .maxUsers(req.getMaxUsers() != null ? req.getMaxUsers() : plan.getDefaultMaxUsers())
                .maxWorkOrdersPerMonth(req.getMaxWorkOrdersPerMonth() != null
                        ? req.getMaxWorkOrdersPerMonth()
                        : plan.getDefaultMaxWorkOrdersPerMonth())
                .isActive(true)
                .notes(req.getNotes())
                .build();

        return toResponse(subscriptionRepo.save(sub));
    }

    /** Updates plan, dates, limits, or active flag. */
    public SubscriptionResponse update(UUID id, UpdateSubscriptionRequest req) {
        Subscription sub = findOrThrow(id);

        if (req.getPlan() != null)                         sub.setPlan(req.getPlan());
        if (req.getStartDate() != null)                    sub.setStartDate(req.getStartDate());
        if (req.getEndDate() != null)                      sub.setEndDate(req.getEndDate());
        if (req.getMaxUsers() != null)                     sub.setMaxUsers(req.getMaxUsers());
        if (req.getMaxWorkOrdersPerMonth() != null)        sub.setMaxWorkOrdersPerMonth(req.getMaxWorkOrdersPerMonth());
        if (req.getActive() != null)                       sub.setActive(req.getActive());
        if (req.getNotes() != null)                        sub.setNotes(req.getNotes());

        return toResponse(subscriptionRepo.save(sub));
    }

    /** Renews a subscription by extending the end date. */
    public SubscriptionResponse renew(UUID id, LocalDate newEndDate, String notes) {
        Subscription sub = findOrThrow(id);
        sub.setEndDate(newEndDate);
        sub.setActive(true);
        if (notes != null) sub.setNotes(notes);
        return toResponse(subscriptionRepo.save(sub));
    }

    /** Soft-deletes the subscription record. */
    public void revoke(UUID id) {
        Subscription sub = findOrThrow(id);
        sub.setDeletedAt(Instant.now());
        subscriptionRepo.save(sub);
    }

    // ── Helpers ─────────────────────────────────────────────

    private Subscription findOrThrow(UUID id) {
        return subscriptionRepo.findById(id)
                .filter(s -> s.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription not found"));
    }

    /** If explicit endDate provided, use it. Else compute from plan's default duration. */
    private LocalDate resolveEndDate(LocalDate explicit, LocalDate start, SubscriptionPlan plan) {
        if (explicit != null) return explicit;
        int days = plan.getDefaultDurationDays();
        // -1 = no expiry (ENTERPRISE)
        return days < 0 ? null : start.plusDays(days);
    }

    SubscriptionResponse toResponse(Subscription s) {
        return SubscriptionResponse.builder()
                .id(s.getId())
                .centerId(s.getCenter().getId())
                .centerName(s.getCenter().getName())
                .centerNameAr(s.getCenter().getNameAr())
                .centerCode(s.getCenter().getCode())
                .plan(s.getPlan().name())
                .planLabelAr(s.getPlan().getLabelAr())
                .planLabelEn(s.getPlan().getLabelEn())
                .startDate(s.getStartDate())
                .endDate(s.getEndDate())
                .maxUsers(s.getMaxUsers())
                .maxWorkOrdersPerMonth(s.getMaxWorkOrdersPerMonth())
                .active(s.isActive())
                .expired(s.isExpired())
                .valid(s.isValid())
                .daysRemaining(s.daysRemaining())
                .notes(s.getNotes())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }
}
