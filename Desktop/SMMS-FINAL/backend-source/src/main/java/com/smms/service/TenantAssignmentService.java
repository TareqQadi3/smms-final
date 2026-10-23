package com.smms.service;

import com.smms.dto.response.TenantAssignmentCandidateResponse;
import com.smms.dto.response.TenantAssignmentResponse;
import com.smms.entity.Tenant;
import com.smms.entity.User;
import com.smms.entity.UserControlRoomAccess;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.TenantRepository;
import com.smms.repository.UserControlRoomAccessRepository;
import com.smms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Explicit, audited repair path for users that have no tenant. Such users cannot sign in (see
 * AuthService), and nothing assigns them automatically, not even when only one tenant exists:
 * the platform admin decides. The chosen tenant must agree with every anchor the user already has
 * (center, region, granted control rooms); an anchor without a tenant cannot be verified and blocks
 * the assignment until the hierarchy itself is fixed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantAssignmentService {

    private final UserRepository userRepo;
    private final TenantRepository tenantRepo;
    private final UserControlRoomAccessRepository crAccessRepo;

    /** Thrown when the target already has a tenant (mapped to 409 by the controller). */
    public static class TenantAlreadyAssignedException extends RuntimeException {
        public TenantAlreadyAssignedException(String msg) { super(msg); }
    }

    @Transactional(readOnly = true)
    public Page<TenantAssignmentCandidateResponse> listTenantless(User actor, Pageable pageable) {
        requireSystemAdmin(actor);
        Specification<User> spec = (root, q, cb) -> cb.and(
            cb.isNull(root.get("deletedAt")),
            cb.isNull(root.get("tenantId")),
            cb.notEqual(root.get("role"), UserRole.system_admin));
        return userRepo.findAll(spec, pageable).map(u -> TenantAssignmentCandidateResponse.builder()
            .id(u.getId()).username(u.getUsername()).role(u.getRole())
            .centerId(u.getCenter() != null ? u.getCenter().getId() : null)
            .regionId(u.getRegion() != null ? u.getRegion().getId() : null)
            .build());
    }

    @Transactional
    public TenantAssignmentResponse assign(UUID userId, UUID tenantId, User actor) {
        requireSystemAdmin(actor);
        User target = userRepo.findById(userId)
            .filter(u -> u.getDeletedAt() == null)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (target.getRole() == UserRole.system_admin) {
            throw new IllegalArgumentException("A platform admin has no tenant");
        }
        if (target.getTenantId() != null) {
            throw new TenantAlreadyAssignedException("User already has a tenant");
        }
        Tenant tenant = tenantRepo.findById(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));
        if (!tenant.isActive()) {
            throw new IllegalArgumentException("Tenant is not active");
        }
        assertAnchorsConsistent(target, tenantId);

        target.setTenantId(tenantId);
        userRepo.save(target);
        // Audit trail: ids only, never names, emails or secrets
        log.warn("AUDIT: tenant assigned to tenant-less user actorId={} targetUserId={} tenantId={}",
            actor.getId(), target.getId(), tenantId);
        return TenantAssignmentResponse.builder().userId(target.getId()).tenantId(tenantId).build();
    }

    private void assertAnchorsConsistent(User target, UUID tenantId) {
        if (target.getCenter() != null) check("center", target.getCenter().getTenantId(), tenantId);
        if (target.getRegion() != null) check("region", target.getRegion().getTenantId(), tenantId);
        for (UserControlRoomAccess grant : crAccessRepo.findByUserId(target.getId())) {
            check("control room", grant.getControlRoom().getTenantId(), tenantId);
        }
    }

    private static void check(String anchor, UUID anchorTenant, UUID wanted) {
        if (anchorTenant == null) {
            throw new IllegalArgumentException(
                "Inconsistent tenant: the user's " + anchor + " has no tenant, fix the hierarchy first");
        }
        if (!Objects.equals(anchorTenant, wanted)) {
            throw new IllegalArgumentException(
                "Inconsistent tenant: the user's " + anchor + " belongs to a different tenant");
        }
    }

    private static void requireSystemAdmin(User actor) {
        if (actor == null || actor.getRole() != UserRole.system_admin) {
            throw new AccessDeniedException("Platform admin only");
        }
    }
}
