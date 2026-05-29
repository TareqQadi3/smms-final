package com.smms.service;

import com.smms.dto.request.CreateMaintenanceTypeRequest;
import com.smms.dto.response.MaintenanceTypeResponse;
import com.smms.entity.ControlRoom;
import com.smms.entity.MaintenanceType;
import com.smms.enums.ControlRoomType;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.ControlRoomRepository;
import com.smms.repository.MaintenanceTypeRepository;
import com.smms.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class MaintenanceTypeService {

    private final MaintenanceTypeRepository repo;
    private final ControlRoomRepository controlRoomRepo;

    // ── List ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<MaintenanceTypeResponse> listAll() {
        UUID tenantId = TenantContext.get();
        if (tenantId != null)
            return repo.findAllByTenantIdAndIsActiveTrueOrderByNameAsc(tenantId)
                .stream().map(this::toResponse).toList();
        return repo.findAllByIsActiveTrueOrderByNameAsc()
            .stream().map(this::toResponse).toList();
    }

    /** ISSUE 3 FIX: filter maintenance types by the control room's type + tenant. */
    @Transactional(readOnly = true)
    public List<MaintenanceTypeResponse> listByControlRoom(UUID controlRoomId) {
        ControlRoom cr = controlRoomRepo.findById(controlRoomId)
            .orElseThrow(() -> new ResourceNotFoundException("ControlRoom not found"));
        UUID tenantId = TenantContext.get();
        if (tenantId != null)
            return repo.findAllByTenantIdAndControlRoomTypeAndIsActiveTrueOrderByNameAsc(tenantId, cr.getType())
                .stream().map(this::toResponse).toList();
        return repo.findAllByControlRoomTypeAndIsActiveTrueOrderByNameAsc(cr.getType())
            .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<MaintenanceTypeResponse> listByType(ControlRoomType type) {
        UUID tenantId = TenantContext.get();
        if (tenantId != null)
            return repo.findAllByTenantIdAndControlRoomTypeAndIsActiveTrueOrderByNameAsc(tenantId, type)
                .stream().map(this::toResponse).toList();
        return repo.findAllByControlRoomTypeAndIsActiveTrueOrderByNameAsc(type)
            .stream().map(this::toResponse).toList();
    }

    // ── CRUD ──────────────────────────────────────────────────

    public MaintenanceTypeResponse create(CreateMaintenanceTypeRequest req) {
        MaintenanceType mt = MaintenanceType.builder()
            .name(req.getName())
            .nameAr(req.getNameAr())
            .controlRoomType(req.getControlRoomType())
            .isActive(true)
            .build();
        mt.setTenantId(TenantContext.get());
        return toResponse(repo.save(mt));
    }

    public MaintenanceTypeResponse update(UUID id, CreateMaintenanceTypeRequest req) {
        MaintenanceType mt = repo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("MaintenanceType not found"));
        if (StringUtils.hasText(req.getName()))   mt.setName(req.getName());
        if (StringUtils.hasText(req.getNameAr())) mt.setNameAr(req.getNameAr());
        if (req.getControlRoomType() != null)     mt.setControlRoomType(req.getControlRoomType());
        return toResponse(repo.save(mt));
    }

    public void delete(UUID id) {
        MaintenanceType mt = repo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("MaintenanceType not found"));
        mt.setActive(false);   // soft-delete
        repo.save(mt);
    }

    // ── Helper ────────────────────────────────────────────────

    private MaintenanceTypeResponse toResponse(MaintenanceType mt) {
        return MaintenanceTypeResponse.builder()
            .id(mt.getId())
            .name(mt.getName())
            .nameAr(mt.getNameAr())
            .controlRoomType(mt.getControlRoomType())
            .active(mt.isActive())
            .build();
    }
}
