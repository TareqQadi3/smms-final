package com.smms.service;

import com.smms.dto.request.CreateRegionRequest;
import com.smms.dto.request.UpdateRegionRequest;
import com.smms.dto.response.RegionResponse;
import com.smms.entity.Region;
import com.smms.exception.ResourceNotFoundException;
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
public class RegionService {

    private final RegionRepository regionRepo;

    @Transactional(readOnly = true)
    public List<RegionResponse> listAll() {
        return regionRepo.findAllByIsActiveTrueAndDeletedAtIsNull()
            .stream()
            .map(this::toResponse)
            .toList();
    }

    public RegionResponse create(CreateRegionRequest req) {
        if (regionRepo.findByCodeAndDeletedAtIsNull(req.getCode()).isPresent()) {
            throw new IllegalArgumentException("Region code already exists: " + req.getCode());
        }
        Region saved = regionRepo.save(Region.builder()
            .name(req.getName())
            .nameAr(req.getNameAr())
            .code(req.getCode().toUpperCase())
            .isActive(true)
            .build());
        return toResponse(saved);
    }

    public RegionResponse update(UUID id, UpdateRegionRequest req) {
        Region region = regionRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Region not found"));
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
