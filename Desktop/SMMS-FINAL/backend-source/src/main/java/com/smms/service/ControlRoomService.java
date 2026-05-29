package com.smms.service;

import com.smms.dto.request.CreateControlRoomRequest;
import com.smms.dto.request.UpdateControlRoomRequest;
import com.smms.dto.response.ControlRoomResponse;
import com.smms.entity.Center;
import com.smms.entity.ControlRoom;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.ControlRoomRepository;
import com.smms.security.TenantSecurityService;
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

    public ControlRoomResponse create(CreateControlRoomRequest req) {
        Center center = tenantSecurity.requireCenter(req.getCenterId());

        ControlRoom cr = ControlRoom.builder()
            .center(center)
            .name(req.getName())
            .nameAr(req.getNameAr())
            .code(req.getCode() != null ? req.getCode().toUpperCase() : null)
            .type(req.getType())
            .isActive(true)
            .build();
        cr.setTenantId(TenantContext.get());
        ControlRoom saved = controlRoomRepo.save(cr);
        return toResponse(saved);
    }

    public ControlRoomResponse update(UUID id, UpdateControlRoomRequest req) {
        ControlRoom cr = tenantSecurity.requireControlRoom(id);
        if (req.getCenterId() != null) {
            cr.setCenter(tenantSecurity.requireCenter(req.getCenterId()));
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
