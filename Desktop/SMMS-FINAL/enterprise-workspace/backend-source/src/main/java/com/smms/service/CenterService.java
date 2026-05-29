package com.smms.service;

import com.smms.dto.request.CreateCenterRequest;
import com.smms.dto.request.UpdateCenterRequest;
import com.smms.dto.response.CenterResponse;
import com.smms.entity.Center;
import com.smms.entity.Region;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.CenterRepository;
import com.smms.repository.RegionRepository;
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
    private final RegionRepository regionRepo;

    @Transactional(readOnly = true)
    public List<CenterResponse> list(UUID regionId) {
        List<Center> centers = (regionId != null)
            ? centerRepo.findAllByRegionIdAndDeletedAtIsNull(regionId)
            : centerRepo.findAllByDeletedAtIsNull();
        return centers.stream().map(this::toResponse).toList();
    }

    public CenterResponse create(CreateCenterRequest req) {
        if (centerRepo.findByCodeAndDeletedAtIsNull(req.getCode()).isPresent()) {
            throw new IllegalArgumentException("Center code already exists: " + req.getCode());
        }
        Region region = regionRepo.findById(req.getRegionId())
            .orElseThrow(() -> new ResourceNotFoundException("Region not found"));

        Center saved = centerRepo.save(Center.builder()
            .region(region)
            .name(req.getName())
            .nameAr(req.getNameAr())
            .code(req.getCode().toUpperCase())
            .address(req.getAddress())
            .isActive(true)
            .build());
        return toResponse(saved);
    }

    public CenterResponse update(UUID id, UpdateCenterRequest req) {
        Center center = centerRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Center not found"));
        if (req.getRegionId() != null) {
            center.setRegion(regionRepo.findById(req.getRegionId())
                .orElseThrow(() -> new ResourceNotFoundException("Region not found")));
        }
        if (StringUtils.hasText(req.getName()))   center.setName(req.getName());
        if (StringUtils.hasText(req.getNameAr())) center.setNameAr(req.getNameAr());
        if (StringUtils.hasText(req.getCode()))   center.setCode(req.getCode().toUpperCase());
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
