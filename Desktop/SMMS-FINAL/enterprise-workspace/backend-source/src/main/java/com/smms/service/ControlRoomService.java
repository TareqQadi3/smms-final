package com.smms.service;

import com.smms.dto.request.CreateControlRoomRequest;
import com.smms.dto.request.UpdateControlRoomRequest;
import com.smms.dto.response.ControlRoomResponse;
import com.smms.entity.Center;
import com.smms.entity.ControlRoom;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.CenterRepository;
import com.smms.repository.ControlRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ControlRoomService {

    private final ControlRoomRepository controlRoomRepo;
    private final CenterRepository      centerRepo;

    @Transactional(readOnly = true)
    public List<ControlRoomResponse> list(UUID centerId) {
        List<ControlRoom> rooms = (centerId != null)
            ? controlRoomRepo.findAllByCenterIdAndIsActiveTrue(centerId)
            : controlRoomRepo.findAllByIsActiveTrue();
        return rooms.stream().map(this::toResponse).toList();
    }

    public ControlRoomResponse create(CreateControlRoomRequest req) {
        Center center = centerRepo.findById(req.getCenterId())
            .orElseThrow(() -> new ResourceNotFoundException("Center not found"));

        ControlRoom saved = controlRoomRepo.save(ControlRoom.builder()
            .center(center)
            .name(req.getName())
            .nameAr(req.getNameAr())
            .code(req.getCode() != null ? req.getCode().toUpperCase() : null)
            .type(req.getType())
            .isActive(true)
            .build());
        return toResponse(saved);
    }

    public ControlRoomResponse update(UUID id, UpdateControlRoomRequest req) {
        ControlRoom cr = controlRoomRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Control Room not found"));
        if (req.getCenterId() != null) {
            cr.setCenter(centerRepo.findById(req.getCenterId())
                .orElseThrow(() -> new ResourceNotFoundException("Center not found")));
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
