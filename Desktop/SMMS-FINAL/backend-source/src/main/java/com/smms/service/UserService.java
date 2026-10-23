package com.smms.service;

import com.smms.dto.request.CreateUserRequest;
import com.smms.dto.request.UpdateUserRequest;
import com.smms.dto.response.UserResponse;
import com.smms.entity.*;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.mapper.UserMapper;
import com.smms.repository.*;
import com.smms.security.TenantSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.smms.util.InputSanitizer;
import com.smms.util.TenantContext;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private static final java.util.Set<UserRole> REGION_ROLES = java.util.Set.of(
        UserRole.region_supervisor
    );
    private static final java.util.Set<UserRole> CENTER_ROLES = java.util.Set.of(
        UserRole.hospital_supervisor, UserRole.control_room, UserRole.technician,
        UserRole.maintenance_manager, UserRole.electrical_manager, UserRole.warehouse,
        UserRole.operating_company_manager, UserRole.resident, UserRole.department
    );

    private final UserRepository userRepo;
    private final UserControlRoomAccessRepository accessRepo;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper mapper;
    private final PlanEnforcementService planEnforcement;
    private final TenantSecurityService tenantSecurity;

    public UserResponse create(CreateUserRequest req, User actor) {
        // ── Plan limit enforcement ─────────────────────────────
        planEnforcement.checkUserLimit();

        // ── Duplicate checks ───────────────────────────────────
        if (userRepo.existsByUsernameAndDeletedAtIsNull(req.getUsername())) {
            throw new IllegalArgumentException("Username already exists: " + req.getUsername());
        }
        if (StringUtils.hasText(req.getEmail())
                && userRepo.existsByEmailAndDeletedAtIsNull(req.getEmail())) {
            throw new IllegalArgumentException("Email already exists: " + req.getEmail());
        }

        // ── Role-hierarchy enforcement ─────────────────────────
        validateRoleAssignment(req.getRole(), actor);

        // ── Validate required links per role ───────────────────
        if (REGION_ROLES.contains(req.getRole()) && req.getRegionId() == null) {
            throw new IllegalArgumentException("Region is required for role: " + req.getRole());
        }
        if (CENTER_ROLES.contains(req.getRole()) && req.getCenterId() == null) {
            throw new IllegalArgumentException("Center is required for role: " + req.getRole());
        }

        // ── Build user ─────────────────────────────────────────
        // email is unique+NOT NULL in DB; generate placeholder if not provided
        String email = StringUtils.hasText(req.getEmail())
            ? req.getEmail()
            : req.getUsername() + "@smms.local";

        User user = User.builder()
            .username(req.getUsername().trim())
            .email(email)
            .passwordHash(passwordEncoder.encode(req.getPassword()))
            .fullName(InputSanitizer.clean(req.getFullName(), 200))
            .fullNameAr(InputSanitizer.clean(req.getFullNameAr(), 200))
            .phone(req.getPhone())
            .role(req.getRole())
            .isActive(true)
            // Resident fields
            .complexName(InputSanitizer.clean(req.getComplexName(), 200))
            .buildingNumber(InputSanitizer.clean(req.getBuildingNumber(), 50))
            .unitNumber(InputSanitizer.clean(req.getUnitNumber(), 50))
            // Department fields
            .departmentName(InputSanitizer.clean(req.getDepartmentName(), 200))
            .departmentSupervisor(InputSanitizer.clean(req.getDepartmentSupervisor(), 200))
            .departmentEmail(req.getDepartmentEmail())
            .extensionNumber(InputSanitizer.clean(req.getExtensionNumber(), 20))
            // Custom
            .jobTitle(InputSanitizer.clean(req.getJobTitle(), 200))
            .build();

        if (req.getCenterId() != null) {
            user.setCenter(tenantSecurity.requireCenter(req.getCenterId()));
        }
        if (req.getRegionId() != null) {
            user.setRegion(tenantSecurity.requireRegion(req.getRegionId()));
        }

        user.setTenantId(TenantContext.get());
        User saved = userRepo.save(user);

        // ── Auto-link control rooms ────────────────────────────
        java.util.Set<java.util.UUID> crIds = new java.util.LinkedHashSet<>();
        if (req.getControlRoomId()  != null) crIds.add(req.getControlRoomId());
        if (req.getControlRoomIds() != null) crIds.addAll(req.getControlRoomIds());
        for (java.util.UUID crId : crIds) {
            ControlRoom cr = tenantSecurity.requireControlRoom(crId);
            if (!accessRepo.existsByUserIdAndControlRoomId(saved.getId(), cr.getId())) {
                accessRepo.save(UserControlRoomAccess.builder()
                    .user(saved).controlRoom(cr).build());
            }
        }

        return mapper.toResponse(saved);
    }

    private static final Set<UserRole> REGION_INVISIBLE_ROLES = Set.of(
        UserRole.system_admin, UserRole.region_supervisor
    );

    @Transactional(readOnly = true)
    public Page<UserResponse> list(User actor, UUID centerId, UserRole role, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        UUID tenantId = TenantContext.get(); // null for platform system_admin

        // region_supervisor: scoped to own region AND tenant, never sees system_admin
        if (actor.getRole() == UserRole.region_supervisor) {
            if (actor.getRegion() == null) return Page.empty(pageable);
            return userRepo.findAllInRegionExcludingRoles(
                    actor.getRegion().getId(), REGION_INVISIBLE_ROLES, tenantId, pageable)
                .map(mapper::toResponse);
        }

        // Platform admin (null tenantId) → no tenant filter; else tenant-scoped
        if (tenantId != null) {
            if (centerId != null && role != null)
                return userRepo.findAllByTenantIdAndCenterIdAndRoleAndDeletedAtIsNull(tenantId, centerId, role, pageable).map(mapper::toResponse);
            if (centerId != null)
                return userRepo.findAllByTenantIdAndCenterIdAndDeletedAtIsNull(tenantId, centerId, pageable).map(mapper::toResponse);
            if (role != null)
                return userRepo.findAllByTenantIdAndRoleAndDeletedAtIsNull(tenantId, role, pageable).map(mapper::toResponse);
            return userRepo.findAllByTenantIdAndDeletedAtIsNull(tenantId, pageable).map(mapper::toResponse);
        }

        // system_admin — returns all (no tenant filter), with optional filters
        if (centerId != null && role != null)
            return userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(centerId, role, pageable).map(mapper::toResponse);
        if (centerId != null)
            return userRepo.findAllByCenterIdAndDeletedAtIsNull(centerId, pageable).map(mapper::toResponse);
        if (role != null)
            return userRepo.findAllByRoleAndDeletedAtIsNull(role, pageable).map(mapper::toResponse);
        return userRepo.findAllByDeletedAtIsNull(pageable).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        return mapper.toResponse(tenantSecurity.requireUser(id));
    }

    public void changePassword(UUID id, String currentPassword, String newPassword) {
        User user = tenantSecurity.requireUser(id);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("New password must be at least 8 characters");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepo.save(user);
    }

    public UserResponse update(UUID id, UpdateUserRequest req, User actor) {
        User user = tenantSecurity.requireUser(id);
        // ISSUE 1 FIX: prevent privilege escalation
        assertCanManage(actor, user);
        assertCanApplyScopeChanges(actor, user, req);

        if (StringUtils.hasText(req.getFullName()))  user.setFullName(InputSanitizer.clean(req.getFullName(), 200));
        if (req.getFullNameAr()  != null)            user.setFullNameAr(InputSanitizer.clean(req.getFullNameAr(), 200));
        if (StringUtils.hasText(req.getEmail()))     user.setEmail(req.getEmail());
        if (req.getPhone()       != null)            user.setPhone(req.getPhone());
        if (req.getRole()        != null)            user.setRole(req.getRole());
        if (req.getCenterId()    != null) {
            user.setCenter(tenantSecurity.requireCenter(req.getCenterId()));
        }
        if (req.getRegionId() != null) {
            user.setRegion(tenantSecurity.requireRegion(req.getRegionId()));
        }
        return mapper.toResponse(userRepo.save(user));
    }

    /**
     * Self-service profile update (PUT /api/users/me). Only personal contact fields are
     * applied; role, center and region in the request are ignored so a user can never
     * change their own privileges or scope.
     */
    public UserResponse updateMe(UUID id, UpdateUserRequest req) {
        User user = tenantSecurity.requireUser(id);
        if (StringUtils.hasText(req.getFullName()))  user.setFullName(InputSanitizer.clean(req.getFullName(), 200));
        if (req.getFullNameAr()  != null)            user.setFullNameAr(InputSanitizer.clean(req.getFullNameAr(), 200));
        if (StringUtils.hasText(req.getEmail()))     user.setEmail(req.getEmail());
        if (req.getPhone()       != null)            user.setPhone(req.getPhone());
        return mapper.toResponse(userRepo.save(user));
    }

    public void toggleActive(UUID id, boolean active, User actor) {
        User user = tenantSecurity.requireUser(id);
        assertCanManage(actor, user);
        user.setActive(active);
        userRepo.save(user);
    }

    public void resetPassword(UUID id, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        User user = tenantSecurity.requireUser(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepo.save(user);
    }

    public void grantControlRoomAccess(UUID userId, UUID controlRoomId) {
        if (accessRepo.existsByUserIdAndControlRoomId(userId, controlRoomId)) return;
        User user = tenantSecurity.requireUser(userId);
        ControlRoom cr = tenantSecurity.requireControlRoom(controlRoomId);
        accessRepo.save(UserControlRoomAccess.builder().user(user).controlRoom(cr).build());
    }

    // ── Private helpers ────────────────────────────────────────

    /** ISSUE 1 FIX: strict hierarchy enforcement */
    private void assertCanManage(User actor, User target) {
        if (actor == null || actor.getRole() == UserRole.system_admin) return;
        // region_supervisor cannot touch system_admin or other region_supervisors
        if (actor.getRole() == UserRole.region_supervisor) {
            if (target.getRole() == UserRole.system_admin
                    || target.getRole() == UserRole.region_supervisor) {
                throw new org.springframework.security.access.AccessDeniedException(
                    "Insufficient privilege to manage this account");
            }
            // must be in same region
            UUID actorRegion = actor.getRegion() != null ? actor.getRegion().getId() : null;
            UUID targetRegion = target.getRegion() != null ? target.getRegion().getId()
                : (target.getCenter() != null && target.getCenter().getRegion() != null
                    ? target.getCenter().getRegion().getId() : null);
            if (actorRegion == null || !actorRegion.equals(targetRegion)) {
                throw new org.springframework.security.access.AccessDeniedException(
                    "User is not in your region");
            }
        }
        // hospital_supervisor cannot touch admin/supervisor level
        if (actor.getRole() == UserRole.hospital_supervisor) {
            if (target.getRole() == UserRole.system_admin
                    || target.getRole() == UserRole.region_supervisor
                    || target.getRole() == UserRole.hospital_supervisor) {
                throw new org.springframework.security.access.AccessDeniedException(
                    "Insufficient privilege to manage this account");
            }
        }
    }

    /**
     * Admin-update scope rules (same hierarchy as create(), see validateRoleAssignment and the
     * @PreAuthorize on UserController.update):
     *  - nobody may change their own role/center/region through the admin endpoint;
     *  - a new role must be one the actor could create (non-system_admin cannot grant
     *    system_admin, region_supervisor cannot grant region_supervisor, etc.);
     *  - only system_admin may change a region link; region_supervisor may only move users
     *    to a center inside their own region; hospital_supervisor only to their own center.
     */
    private void assertCanApplyScopeChanges(User actor, User target, UpdateUserRequest req) {
        if (actor == null) return; // system bootstrap
        boolean self = actor.getId() != null && actor.getId().equals(target.getId());
        if (actor.getRole() == UserRole.system_admin) {
            if (self && req.getRole() != null && req.getRole() != target.getRole()) {
                throw new org.springframework.security.access.AccessDeniedException(
                    "You cannot change your own role");
            }
            return;
        }
        if (self && (req.getRole() != null || req.getCenterId() != null || req.getRegionId() != null)) {
            throw new org.springframework.security.access.AccessDeniedException(
                "You cannot change your own role or scope");
        }
        if (req.getRole() != null) validateRoleAssignment(req.getRole(), actor);
        if (req.getRegionId() != null) {
            throw new org.springframework.security.access.AccessDeniedException(
                "Only a system administrator can change a user's region");
        }
        if (req.getCenterId() != null) {
            if (actor.getRole() == UserRole.hospital_supervisor) {
                UUID own = actor.getCenter() != null ? actor.getCenter().getId() : null;
                if (own == null || !own.equals(req.getCenterId())) {
                    throw new org.springframework.security.access.AccessDeniedException(
                        "Center is outside your scope");
                }
            } else if (actor.getRole() == UserRole.region_supervisor) {
                Center c = tenantSecurity.requireCenter(req.getCenterId());
                UUID actorRegion = actor.getRegion() != null ? actor.getRegion().getId() : null;
                if (actorRegion == null || c.getRegion() == null
                        || !actorRegion.equals(c.getRegion().getId())) {
                    throw new org.springframework.security.access.AccessDeniedException(
                        "Center is outside your region");
                }
            }
        }
    }

    private void validateRoleAssignment(UserRole targetRole, User actor) {
        if (actor == null) return; // system bootstrap
        switch (actor.getRole()) {
            case system_admin -> { /* can create any role */ }
            case region_supervisor -> {
                if (targetRole == UserRole.system_admin || targetRole == UserRole.region_supervisor) {
                    throw new IllegalArgumentException(
                        "Region supervisors cannot create system_admin or region_supervisor accounts");
                }
            }
            case hospital_supervisor -> {
                if (targetRole == UserRole.system_admin
                        || targetRole == UserRole.region_supervisor
                        || targetRole == UserRole.hospital_supervisor) {
                    throw new IllegalArgumentException(
                        "Hospital supervisors cannot create admin/supervisor-level accounts");
                }
            }
            default -> throw new IllegalArgumentException(
                "Your role is not authorized to create users");
        }
    }
}
