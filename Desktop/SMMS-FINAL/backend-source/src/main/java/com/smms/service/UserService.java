package com.smms.service;

import com.smms.auth.AuthSessionService;
import com.smms.dto.request.CreateUserRequest;
import com.smms.dto.request.UpdateUserRequest;
import com.smms.dto.response.UserResponse;
import com.smms.entity.*;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.mapper.UserMapper;
import com.smms.repository.*;
import com.smms.repository.UserSpecs;
import com.smms.security.AdminScopePolicy;
import com.smms.security.TenantSecurityService;
import com.smms.security.AdminScopePolicy.UserListScope;
import org.springframework.security.access.AccessDeniedException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.smms.util.InputSanitizer;
import com.smms.util.TenantContext;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepo;
    private final UserControlRoomAccessRepository accessRepo;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper mapper;
    private final PlanEnforcementService planEnforcement;
    private final TenantSecurityService tenantSecurity;
    private final AdminScopePolicy policy;
    private final UserAccountValidator validator;

    /**
     * Session revocation hooks. Field-injected (not a constructor argument) so the many unit tests
     * that build this service by hand keep compiling; null only in such tests.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuthSessionService authSessions;

    public UserResponse create(CreateUserRequest req, User actor) {
        // ── Role hierarchy first: a forbidden role is 403 whatever the ids say ──
        policy.assertCanAssignRole(actor, req.getRole());
        // ── Same field rules as the bulk import (400) ──
        final String username = req.getUsername() == null ? null : req.getUsername().trim();
        validator.require(validator.validateFields(new UserAccountValidator.Fields(
            username, req.getPassword(), req.getEmail(), req.getPhone(), req.getFullName(),
            req.getRole(), req.getDepartmentName(), req.getDepartmentEmail())));
        boolean platformRole = req.getRole() == UserRole.system_admin;
        boolean actorIsAdmin = actor == null || actor.getRole() == UserRole.system_admin;

        // ── Resolve and scope-check every referenced object (404 when outside the actor's scope) ──
        UUID centerId = req.getCenterId();
        if (centerId == null && actor != null && actor.getRole() == UserRole.hospital_supervisor) {
            // A hospital supervisor always works inside their own center
            centerId = actor.getCenter() != null ? actor.getCenter().getId() : null;
        }
        Center center = null;
        if (centerId != null) {
            center = tenantSecurity.requireCenter(centerId);
            policy.assertCenterInScope(actor, center);
        }
        Region region = null;
        if (req.getRegionId() != null) {
            region = tenantSecurity.requireRegion(req.getRegionId());
            policy.assertRegionInScope(actor, region);
        }

        // ── Validate required links per role ───────────────────
        if (!actorIsAdmin && center == null) {
            // Scoped creators can only create users that live inside their scope
            throw new IllegalArgumentException("Center is required");
        }
        validator.require(validator.validateLinks(req.getRole(), center, region, req.getDepartmentName()));

        List<ControlRoom> rooms = new ArrayList<>();
        java.util.Set<UUID> crIds = new LinkedHashSet<>();
        if (req.getControlRoomId()  != null) crIds.add(req.getControlRoomId());
        if (req.getControlRoomIds() != null) crIds.addAll(req.getControlRoomIds());
        for (UUID crId : crIds) {
            ControlRoom cr = tenantSecurity.requireControlRoom(crId);
            policy.assertControlRoomInScope(actor, cr);
            validator.assertGrantAllowed(actor, req.getRole(), center, cr);
            rooms.add(cr);
        }

        // ── Tenant: derived from the parents, never null for a tenant user ──
        UUID tenantId = null;
        if (!platformRole) {
            List<BaseEntity> anchors = new ArrayList<>();
            anchors.add(center);
            anchors.add(region);
            anchors.addAll(rooms);
            tenantId = policy.resolveTenantForNewEntity(req.getTenantId(), anchors.toArray(new BaseEntity[0]));
        }

        // ── Plan limit (of the tenant the user is really created in) ──
        planEnforcement.checkUserLimit(tenantId, 1);

        // ── Duplicate checks ───────────────────────────────────
        if (userRepo.existsByUsernameIgnoreCaseAndDeletedAtIsNull(username)) {
            throw new IllegalArgumentException("Username already exists: " + username);
        }
        if (StringUtils.hasText(req.getEmail())
                && userRepo.existsByEmailAndDeletedAtIsNull(req.getEmail())) {
            throw new IllegalArgumentException("Email already exists: " + req.getEmail());
        }

        // ── Build user ─────────────────────────────────────────
        // email is unique+NOT NULL in DB; generate placeholder if not provided
        String email = StringUtils.hasText(req.getEmail())
            ? req.getEmail()
            : username + "@smms.local";

        User user = User.builder()
            .username(username)
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

        user.setCenter(center);
        user.setRegion(region);
        user.setTenantId(tenantId);
        User saved = userRepo.save(user);

        // ── Auto-link control rooms ────────────────────────────
        for (ControlRoom cr : rooms) {
            if (!accessRepo.existsByUserIdAndControlRoomId(saved.getId(), cr.getId())) {
                accessRepo.save(UserControlRoomAccess.builder()
                    .user(saved).controlRoom(cr).build());
            }
        }

        return mapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> list(User actor, UUID centerId, UserRole role, Pageable pageable) {
        UserListScope scope = policy.resolveUserListScope(actor);
        if (scope.kind() == AdminScopePolicy.Kind.NONE) {
            // The controller already restricts the roles; this is defence in depth and also
            // covers supervisors that have no center/region assigned
            throw new AccessDeniedException("Not allowed to list users");
        }
        UUID tenantId = TenantContext.get(); // null for platform system_admin
        return userRepo.findAll(UserSpecs.listFor(scope, tenantId, centerId, role), pageable)
            .map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        return mapper.toResponse(tenantSecurity.requireUser(id));
    }

    public void changePassword(UUID id, String currentPassword, String newPassword) {
        changePassword(id, currentPassword, newPassword, null);
    }

    /**
     * Self-service change. All OTHER login sessions of the user end (a stolen refresh token stops
     * working); {@code currentSid}, the caller's own session, stays. Clears the must-change flag.
     */
    public void changePassword(UUID id, String currentPassword, String newPassword, UUID currentSid) {
        User user = tenantSecurity.requireUser(id);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (newPassword == null || newPassword.length() < UserAccountValidator.PASSWORD_MIN
                || newPassword.length() > UserAccountValidator.PASSWORD_MAX) {
            throw new IllegalArgumentException("New password must be 8-128 characters");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepo.save(user);
        if (authSessions != null) {
            authSessions.revokeOthers(user.getId(), currentSid, AuthSessionService.REASON_PASSWORD_CHANGED);
        }
    }

    public UserResponse update(UUID id, UpdateUserRequest req, User actor) {
        User user = tenantSecurity.requireUser(id);
        // Scope (404) and rank (403) of the user being changed
        policy.assertCanManageUser(actor, user);
        assertCanApplyScopeChanges(actor, user, req);
        validator.require(validator.validateContact(req.getEmail(), req.getPhone(), req.getFullName()));
        if (StringUtils.hasText(req.getDepartmentEmail())) {
            validator.require(validator.validateContact(req.getDepartmentEmail(), null, null));
        }

        UserRole finalRole = req.getRole() != null ? req.getRole() : user.getRole();
        Center finalCenter = user.getCenter();
        Region finalRegion = user.getRegion();
        boolean linksChanged = req.getRole() != null && req.getRole() != user.getRole();
        String finalDepartmentName = StringUtils.hasText(req.getDepartmentName())
            ? InputSanitizer.clean(req.getDepartmentName(), 200) : user.getDepartmentName();
        if (req.getCenterId() != null) {
            finalCenter = tenantSecurity.requireCenter(req.getCenterId());
            policy.assertCenterInScope(actor, finalCenter);
            linksChanged = true;
        }
        if (req.getRegionId() != null) {
            finalRegion = tenantSecurity.requireRegion(req.getRegionId());
            policy.assertRegionInScope(actor, finalRegion);
            linksChanged = true;
        }
        if (linksChanged) {
            // Re-validate the role/center/region invariants of the resulting state
            validator.require(validator.validateLinks(finalRole, finalCenter, finalRegion, finalDepartmentName));
            if (finalRole == UserRole.system_admin) {
                user.setTenantId(null); // platform-level account, same invariant as DataInitializer
            } else {
                if (user.getTenantId() == null) {
                    user.setTenantId(policy.resolveTenantForNewEntity(null, finalCenter, finalRegion));
                }
                policy.assertSameTenant(user, finalCenter, finalRegion);
            }
        }
        assertEmailAvailable(user, req.getEmail());

        if (StringUtils.hasText(req.getFullName()))  user.setFullName(InputSanitizer.clean(req.getFullName(), 200));
        if (req.getFullNameAr()  != null)            user.setFullNameAr(InputSanitizer.clean(req.getFullNameAr(), 200));
        if (StringUtils.hasText(req.getEmail()))     user.setEmail(req.getEmail());
        if (req.getPhone()       != null)            user.setPhone(req.getPhone());
        if (finalRole == UserRole.department) {
            user.setDepartmentName(finalDepartmentName);
            if (StringUtils.hasText(req.getDepartmentEmail())) user.setDepartmentEmail(req.getDepartmentEmail());
        }
        revokeGrantsOutsideCenter(user, finalCenter);
        user.setRole(finalRole);
        user.setCenter(finalCenter);
        user.setRegion(finalRegion);
        return mapper.toResponse(userRepo.save(user));
    }

    /**
     * Self-service profile update (PUT /api/users/me). Only personal contact fields are
     * applied; role, center and region in the request are ignored so a user can never
     * change their own privileges or scope.
     */
    public UserResponse updateMe(UUID id, UpdateUserRequest req) {
        User user = tenantSecurity.requireUser(id);
        validator.require(validator.validateContact(req.getEmail(), req.getPhone(), req.getFullName()));
        assertEmailAvailable(user, req.getEmail());
        if (StringUtils.hasText(req.getFullName()))  user.setFullName(InputSanitizer.clean(req.getFullName(), 200));
        if (req.getFullNameAr()  != null)            user.setFullNameAr(InputSanitizer.clean(req.getFullNameAr(), 200));
        if (StringUtils.hasText(req.getEmail()))     user.setEmail(req.getEmail());
        if (req.getPhone()       != null)            user.setPhone(req.getPhone());
        return mapper.toResponse(userRepo.save(user));
    }

    public void toggleActive(UUID id, boolean active, User actor) {
        User user = tenantSecurity.requireUser(id);
        policy.assertCanManageUser(actor, user);
        if (!active && actor != null && actor.getId() != null && actor.getId().equals(user.getId())) {
            throw new AccessDeniedException("You cannot deactivate your own account");
        }
        user.setActive(active);
        if (!active) {
            // Cut off every token: sid-bound ones through the revoked sessions, legacy ones by time
            user.setTokensValidAfter(java.time.Instant.now());
        }
        userRepo.save(user);
        if (!active && authSessions != null) {
            authSessions.revokeAllForUser(user.getId(), AuthSessionService.REASON_DEACTIVATED);
        }
    }

    public void resetPassword(UUID id, String newPassword) {
        if (newPassword == null || newPassword.length() < UserAccountValidator.PASSWORD_MIN
                || newPassword.length() > UserAccountValidator.PASSWORD_MAX) {
            throw new IllegalArgumentException("Password must be 8-128 characters");
        }
        User user = tenantSecurity.requireUser(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // The new password is known to the admin: the user must replace it, and old tokens die
        user.setMustChangePassword(true);
        user.setTokensValidAfter(java.time.Instant.now());
        userRepo.save(user);
        if (authSessions != null) {
            authSessions.revokeAllForUser(user.getId(), AuthSessionService.REASON_PASSWORD_RESET);
        }
    }

    /**
     * Grants a control room to a user. The actor must be allowed to manage the user and the control
     * room must be inside the actor's scope; the existence check comes last so a repeated grant is
     * not an oracle for rooms or users the actor cannot see.
     */
    public void grantControlRoomAccess(UUID userId, UUID controlRoomId, User actor) {
        User user = tenantSecurity.requireUser(userId);
        policy.assertCanManageUser(actor, user);
        ControlRoom cr = tenantSecurity.requireControlRoom(controlRoomId);
        policy.assertControlRoomInScope(actor, cr);
        policy.assertSameTenant(user, cr);
        validator.assertGrantAllowed(actor, user.getRole(), user.getCenter(), cr);
        if (accessRepo.existsByUserIdAndControlRoomId(user.getId(), cr.getId())) return;
        accessRepo.save(UserControlRoomAccess.builder().user(user).controlRoom(cr).build());
    }

    // ── Private helpers ────────────────────────────────────────

    /**
     * Admin-update rules on top of AdminScopePolicy.assertCanManageUser:
     *  - nobody may change their own role/center/region through the admin endpoint;
     *  - a new role must be one the actor could create;
     *  - only system_admin may change a region link (center moves are scope-checked by the caller).
     */
    private void assertCanApplyScopeChanges(User actor, User target, UpdateUserRequest req) {
        if (actor == null) return; // system bootstrap
        boolean self = actor.getId() != null && actor.getId().equals(target.getId());
        if (actor.getRole() == UserRole.system_admin) {
            if (self && req.getRole() != null && req.getRole() != target.getRole()) {
                throw new AccessDeniedException("You cannot change your own role");
            }
            if (req.getRole() != null && req.getRole() != target.getRole()) {
                policy.assertCanAssignRole(actor, req.getRole());
            }
            return;
        }
        if (self && (req.getRole() != null || req.getCenterId() != null || req.getRegionId() != null)) {
            throw new AccessDeniedException("You cannot change your own role or scope");
        }
        if (req.getRole() != null) policy.assertCanAssignRole(actor, req.getRole());
        if (req.getRegionId() != null) {
            throw new AccessDeniedException("Only a system administrator can change a user's region");
        }
    }

    /**
     * A user who moves to another center must not keep control rooms of the old one: those grants
     * would keep exposing the old center's tickets to them.
     */
    private void revokeGrantsOutsideCenter(User user, Center newCenter) {
        UUID oldCenterId = user.getCenter() != null ? user.getCenter().getId() : null;
        UUID newCenterId = newCenter != null ? newCenter.getId() : null;
        if (java.util.Objects.equals(oldCenterId, newCenterId)) return;
        for (UserControlRoomAccess grant : accessRepo.findByUserId(user.getId())) {
            ControlRoom room = grant.getControlRoom();
            UUID roomCenter = room != null && room.getCenter() != null ? room.getCenter().getId() : null;
            if (room != null && !java.util.Objects.equals(roomCenter, newCenterId)) {
                accessRepo.deleteByUserIdAndControlRoomId(user.getId(), room.getId());
            }
        }
    }

    private void assertEmailAvailable(User user, String newEmail) {
        if (StringUtils.hasText(newEmail) && !newEmail.equalsIgnoreCase(user.getEmail())
                && userRepo.existsByEmailAndDeletedAtIsNull(newEmail)) {
            throw new IllegalArgumentException("Email already exists: " + newEmail);
        }
    }
}
