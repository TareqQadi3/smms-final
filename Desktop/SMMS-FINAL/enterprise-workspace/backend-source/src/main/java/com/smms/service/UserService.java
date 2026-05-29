package com.smms.service;

import com.smms.dto.request.CreateUserRequest;
import com.smms.dto.request.UpdateUserRequest;
import com.smms.dto.response.UserResponse;
import com.smms.entity.*;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.mapper.UserMapper;
import com.smms.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
    private final CenterRepository centerRepo;
    private final RegionRepository regionRepo;
    private final ControlRoomRepository controlRoomRepo;
    private final UserControlRoomAccessRepository accessRepo;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper mapper;

    public UserResponse create(CreateUserRequest req, User actor) {
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
            .username(req.getUsername())
            .email(email)
            .passwordHash(passwordEncoder.encode(req.getPassword()))
            .fullName(req.getFullName())
            .fullNameAr(req.getFullNameAr())
            .phone(req.getPhone())
            .role(req.getRole())
            .isActive(true)
            // Resident fields
            .complexName(req.getComplexName())
            .buildingNumber(req.getBuildingNumber())
            .unitNumber(req.getUnitNumber())
            // Department fields
            .departmentName(req.getDepartmentName())
            .departmentSupervisor(req.getDepartmentSupervisor())
            .departmentEmail(req.getDepartmentEmail())
            .extensionNumber(req.getExtensionNumber())
            // Custom
            .jobTitle(req.getJobTitle())
            .build();

        if (req.getCenterId() != null) {
            user.setCenter(centerRepo.findById(req.getCenterId())
                .orElseThrow(() -> new ResourceNotFoundException("Center not found")));
        }
        if (req.getRegionId() != null) {
            user.setRegion(regionRepo.findById(req.getRegionId())
                .orElseThrow(() -> new ResourceNotFoundException("Region not found")));
        }

        User saved = userRepo.save(user);

        // ── Auto-link control rooms ────────────────────────────
        java.util.Set<java.util.UUID> crIds = new java.util.LinkedHashSet<>();
        if (req.getControlRoomId()  != null) crIds.add(req.getControlRoomId());
        if (req.getControlRoomIds() != null) crIds.addAll(req.getControlRoomIds());
        for (java.util.UUID crId : crIds) {
            ControlRoom cr = controlRoomRepo.findById(crId)
                .orElseThrow(() -> new ResourceNotFoundException("ControlRoom not found: " + crId));
            if (!accessRepo.existsByUserIdAndControlRoomId(saved.getId(), cr.getId())) {
                accessRepo.save(UserControlRoomAccess.builder()
                    .user(saved).controlRoom(cr).build());
            }
        }

        return mapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> list(UUID centerId, UserRole role, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        if (centerId != null && role != null) {
            return userRepo.findAllByCenterIdAndRoleAndDeletedAtIsNull(centerId, role, pageable)
                .map(mapper::toResponse);
        }
        if (centerId != null) {
            return userRepo.findAllByCenterIdAndDeletedAtIsNull(centerId, pageable)
                .map(mapper::toResponse);
        }
        if (role != null) {
            return userRepo.findAllByRoleAndDeletedAtIsNull(role, pageable)
                .map(mapper::toResponse);
        }
        return userRepo.findAllByDeletedAtIsNull(pageable)
            .map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        return mapper.toResponse(userRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User not found")));
    }

    public void changePassword(UUID id, String currentPassword, String newPassword) {
        User user = userRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("New password must be at least 8 characters");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepo.save(user);
    }

    public UserResponse update(UUID id, UpdateUserRequest req) {
        User user = userRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (StringUtils.hasText(req.getFullName()))  user.setFullName(req.getFullName());
        if (req.getFullNameAr()  != null)            user.setFullNameAr(req.getFullNameAr());
        if (StringUtils.hasText(req.getEmail()))     user.setEmail(req.getEmail());
        if (req.getPhone()       != null)            user.setPhone(req.getPhone());
        if (req.getRole()        != null)            user.setRole(req.getRole());
        if (req.getCenterId()    != null) {
            user.setCenter(centerRepo.findById(req.getCenterId())
                .orElseThrow(() -> new ResourceNotFoundException("Center not found")));
        }
        if (req.getRegionId() != null) {
            user.setRegion(regionRepo.findById(req.getRegionId())
                .orElseThrow(() -> new ResourceNotFoundException("Region not found")));
        }
        return mapper.toResponse(userRepo.save(user));
    }

    public void toggleActive(UUID id, boolean active) {
        User user = userRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setActive(active);
        userRepo.save(user);
    }

    public void grantControlRoomAccess(UUID userId, UUID controlRoomId) {
        if (accessRepo.existsByUserIdAndControlRoomId(userId, controlRoomId)) return;
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        ControlRoom cr = controlRoomRepo.findById(controlRoomId)
            .orElseThrow(() -> new ResourceNotFoundException("ControlRoom not found"));
        accessRepo.save(UserControlRoomAccess.builder().user(user).controlRoom(cr).build());
    }

    // ── Private helpers ────────────────────────────────────────
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
