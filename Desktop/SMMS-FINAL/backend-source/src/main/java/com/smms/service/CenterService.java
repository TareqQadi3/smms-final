package com.smms.service;

import com.smms.dto.request.CreateCenterRequest;
import com.smms.dto.request.UpdateCenterRequest;
import com.smms.dto.response.CenterResponse;
import com.smms.entity.Center;
import com.smms.entity.Region;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.CenterRepository;
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

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CenterService {

    private final CenterRepository centerRepo;
    private final PlanEnforcementService planEnforcement;
    private final TenantSecurityService tenantSecurity;
    private final AdminScopePolicy policy;

    @Transactional(readOnly = true)
    public List<CenterResponse> list(UUID regionId) {
        UUID tenantId = TenantContext.get();
        if (tenantId != null) {
            List<Center> centers = (regionId != null)
                ? centerRepo.findAllByTenantIdAndRegionIdAndDeletedAtIsNull(tenantId, regionId)
                : centerRepo.findAllByTenantIdAndDeletedAtIsNull(tenantId);
            return centers.stream().map(this::toResponse).toList();
        }
        List<Center> centers = (regionId != null)
            ? centerRepo.findAllByRegionIdAndDeletedAtIsNull(regionId)
            : centerRepo.findAllByDeletedAtIsNull();
        return centers.stream().map(this::toResponse).toList();
    }

    /** system_admin: any region of the tenant; region_supervisor: only their own region. */
    public CenterResponse create(CreateCenterRequest req, User actor) {
        // The parent region decides scope (404 when outside) and the tenant of the new center
        Region region = tenantSecurity.requireRegion(req.getRegionId());
        policy.assertRegionInScope(actor, region);
        UUID tenantId = policy.resolveTenantForNewEntity(null, region);
        planEnforcement.checkCenterLimit(tenantId);
        String code = req.getCode().toUpperCase();
        if (centerRepo.findByCodeAndDeletedAtIsNull(code).isPresent()) {
            throw new IllegalArgumentException("Center code already exists: " + req.getCode());
        }

        Center center = Center.builder()
            .region(region)
            .name(req.getName())
            .nameAr(req.getNameAr())
            .code(code)
            .address(req.getAddress())
            .isActive(true)
            .build();
        center.setTenantId(tenantId);
        Center saved = centerRepo.save(center);
        return toResponse(saved);
    }

    /** Moving a center to another region is reserved to system_admin. */
    public CenterResponse update(UUID id, UpdateCenterRequest req, User actor) {
        Center center = tenantSecurity.requireCenter(id);
        policy.assertCenterInScope(actor, center);
        if (req.getRegionId() != null) {
            UUID current = center.getRegion() != null ? center.getRegion().getId() : null;
            if (!req.getRegionId().equals(current)) {
                if (actor == null || actor.getRole() != UserRole.system_admin) {
                    throw new AccessDeniedException("Only a system administrator can move a center to another region");
                }
                Region target = tenantSecurity.requireRegion(req.getRegionId());
                policy.assertSameTenant(center, target);
                center.setRegion(target);
            }
        }
        if (StringUtils.hasText(req.getCode())) {
            String code = req.getCode().toUpperCase();
            if (!code.equals(center.getCode())
                    && centerRepo.findByCodeAndDeletedAtIsNull(code).isPresent()) {
                throw new IllegalArgumentException("Center code already exists: " + req.getCode());
            }
            center.setCode(code);
        }
        if (StringUtils.hasText(req.getName()))   center.setName(req.getName());
        if (StringUtils.hasText(req.getNameAr())) center.setNameAr(req.getNameAr());
        if (req.getAddress() != null)              center.setAddress(req.getAddress());
        return toResponse(centerRepo.save(center));
    }

    CenterResponse toResponse(Center c) {
        CenterResponse.RegionInfo regionInfo = null;
        if (c.getRegion() != null) {
            regionInfo = CenterResponse.RegionInfo.builder()
                .id(c.getRegion().getId())
                .name(c.getRegion().getName())
                .nameAr(c.getRegion().getNameAr())
                .code(c.getRegion().getCode())
                .build();
        }
        return CenterResponse.builder()
            .id(c.getId())
            .name(c.getName())
            .nameAr(c.getNameAr())
            .code(c.getCode())
            .address(c.getAddress())
            .active(c.isActive())
            .region(regionInfo)
            .build();
    }
}
