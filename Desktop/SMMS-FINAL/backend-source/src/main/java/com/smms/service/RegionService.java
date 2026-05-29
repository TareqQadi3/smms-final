package com.smms.service;

import com.smms.dto.request.CreateRegionRequest;
import com.smms.dto.request.UpdateRegionRequest;
import com.smms.dto.response.RegionResponse;
import com.smms.entity.Region;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.RegionRepository;
import com.smms.security.TenantSecurityService;
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
public class RegionService {

    private final RegionRepository regionRepo;
    private final TenantSecurityService tenantSecurity;

    @Transactional(readOnly = true)
    public List<RegionResponse> listAll() {
        UUID tenantId = TenantContext.get();
        if (tenantId != null) {
            return regionRepo.findAllByTenantIdAndIsActiveTrueAndDeletedAtIsNull(tenantId)
                .stream().map(this::toResponse).toList();
        }
        return regionRepo.findAllByIsActiveTrueAndDeletedAtIsNull()
            .stream().map(this::toResponse).toList();
    }

    public RegionResponse create(CreateRegionRequest req) {
        if (regionRepo.findByCodeAndDeletedAtIsNull(req.getCode()).isPresent()) {
            throw new IllegalArgumentException("Region code already exists: " + req.getCode());
        }
        Region region = Region.builder()
            .name(req.getName())
            .nameAr(req.getNameAr())
            .code(req.getCode().toUpperCase())
            .isActive(true)
            .build();
        region.setTenantId(TenantContext.get());
        Region saved = regionRepo.save(region);
        return toResponse(saved);
    }

    public RegionResponse update(UUID id, UpdateRegionRequest req) {
        Region region = tenantSecurity.requireRegion(id);
        if (StringUtils.hasText(req.getName()))   region.setName(req.getName());
        if (StringUtils.hasText(req.getNameAr())) region.setNameAr(req.getNameAr());
        if (StringUtils.hasText(req.getCode()))   region.setCode(req.getCode().toUpperCase());
        return toResponse(regionRepo.save(region));
    }

    private RegionResponse toResponse(Region r) {
        return RegionResponse.builder()
            .id(r.getId())
            .name(r.getName())
            .nameAr(r.getNameAr())
            .code(r.getCode())
            .active(r.isActive())
            .build();
    }
}
