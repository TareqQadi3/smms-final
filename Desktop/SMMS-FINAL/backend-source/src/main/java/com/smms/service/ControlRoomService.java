package com.smms.service;

import com.smms.dto.request.CreateControlRoomRequest;
import com.smms.dto.request.UpdateControlRoomRequest;
import com.smms.dto.response.ControlRoomResponse;
import com.smms.entity.Center;
import com.smms.entity.ControlRoom;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.ControlRoomRepository;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.security.AdminScopePolicy;
import com.smms.security.TenantSecurityService;
import org.springframework.security.access.AccessDeniedException;
import com.smms.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ControlRoomService {

    private final ControlRoomRepository controlRoomRepo;
    private final TenantSecurityService tenantSecurity;
    private final AdminScopePolicy policy;

    @Transactional(readOnly = true)
    public List<ControlRoomResponse> list(UUID centerId) {
        UUID tenantId = TenantContext.get();
        List<ControlRoom> rooms;
        if (tenantId != null) {
            rooms = (centerId != null)
                ? controlRoomRepo.findAllByTenantIdAndCenterIdAndIsActiveTrue(tenantId, centerId)
                : controlRoomRepo.findAllByTenantIdAndIsActiveTrue(tenantId);
        } else {
            rooms = (centerId != null)
                ? controlRoomRepo.findAllByCenterIdAndIsActiveTrue(centerId)
                : controlRoomRepo.findAllByIsActiveTrue();
        }
        return rooms.stream().map(this::toResponse).toList();
    }

    /** Returns only the CRs with the given IDs (user-specific access list). */
    @Transactional(readOnly = true)
    public List<ControlRoomResponse> listByIds(Collection<UUID> ids) {
        UUID tenantId = TenantContext.get();
        return controlRoomRepo.findAllByIdInAndIsActiveTrue(ids)
            .stream()
            .filter(cr -> tenantId == null || tenantId.equals(cr.getTenantId()))
            .map(this::toResponse)
            .toList();
    }

    /** Scope is the parent center: system_admin any, region_supervisor own region, hospital_supervisor own center. */
    public ControlRoomResponse create(CreateControlRoomRequest req, User actor) {
        Center center = tenantSecurity.requireCenter(req.getCenterId());
        policy.assertCenterInScope(actor, center);
        UUID tenantId = policy.resolveTenantForNewEntity(null, center);

        ControlRoom cr = ControlRoom.builder()
            .center(center)
            .name(req.getName())
            .nameAr(req.getNameAr())
            .code(req.getCode() != null ? req.getCode().toUpperCase() : null)
            .type(req.getType())
            .isActive(true)
            .build();
        cr.setTenantId(tenantId);
        ControlRoom saved = controlRoomRepo.save(cr);
        return toResponse(saved);
    }

    /**
     * Moving a control room needs both the old and the new center in the actor's scope; a hospital
     * supervisor only has one center, so they can never move a room.
     */
    public ControlRoomResponse update(UUID id, UpdateControlRoomRequest req, User actor) {
        ControlRoom cr = tenantSecurity.requireControlRoom(id);
        policy.assertControlRoomInScope(actor, cr);
        if (req.getCenterId() != null) {
            UUID current = cr.getCenter() != null ? cr.getCenter().getId() : null;
            if (!req.getCenterId().equals(current)) {
                Center target = tenantSecurity.requireCenter(req.getCenterId());
                policy.assertCenterInScope(actor, target);
                if (actor == null || actor.getRole() == UserRole.hospital_supervisor) {
                    throw new AccessDeniedException("You cannot move a control room to another center");
                }
                policy.assertSameTenant(cr, target);
                cr.setCenter(target);
            }
        }
        if (StringUtils.hasText(req.getName()))   cr.setName(req.getName());
        if (StringUtils.hasText(req.getNameAr())) cr.setNameAr(req.getNameAr());
        if (StringUtils.hasText(req.getCode()))   cr.setCode(req.getCode().toUpperCase());
        if (req.getType() != null)                cr.setType(req.getType());
        return toResponse(controlRoomRepo.save(cr));
    }

    ControlRoomResponse toResponse(ControlRoom cr) {
        ControlRoomResponse.CenterInfo centerInfo = null;
        if (cr.getCenter() != null) {
            centerInfo = ControlRoomResponse.CenterInfo.builder()
                .id(cr.getCenter().getId())
                .name(cr.getCenter().getName())
                .nameAr(cr.getCenter().getNameAr())
                .build();
        }
        return ControlRoomResponse.builder()
            .id(cr.getId())
            .name(cr.getName())
            .nameAr(cr.getNameAr())
            .code(cr.getCode())
            .type(cr.getType())
            .active(cr.isActive())
            .center(centerInfo)
            .build();
    }
}
