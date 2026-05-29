package com.smms.security;

import com.smms.entity.*;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.repository.*;
import com.smms.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Central tenant-scoped entity resolution and work-order access checks.
 * Platform admin (null tenant in context) uses findById with soft-delete filter only.
 */
@Service
@RequiredArgsConstructor
public class TenantSecurityService {

    private final WorkOrderRepository workOrderRepo;
    private final UserRepository userRepo;
    private final RegionRepository regionRepo;
    private final CenterRepository centerRepo;
    private final ControlRoomRepository controlRoomRepo;

    public void assertSameTenant(UUID entityTenantId) {
        UUID ctx = TenantContext.get();
        if (ctx == null) return;
        if (entityTenantId == null || !ctx.equals(entityTenantId)) {
            throw new AccessDeniedException("Cross-tenant access denied");
        }
    }

    public WorkOrder requireWorkOrder(UUID id) {
        UUID tenantId = TenantContext.get();
        WorkOrder wo = (tenantId != null)
            ? workOrderRepo.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkOrder not found: " + id))
            : workOrderRepo.findById(id)
                .filter(w -> w.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("WorkOrder not found: " + id));
        assertSameTenant(wo.getTenantId());
        return wo;
    }

    public User requireUser(UUID id) {
        UUID tenantId = TenantContext.get();
        User user = (tenantId != null)
            ? userRepo.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"))
            : userRepo.findById(id)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        assertSameTenant(user.getTenantId());
        return user;
    }

    public User requireUser(UUID id, UserRole requiredRole) {
        User user = requireUser(id);
        if (user.getRole() != requiredRole) {
            throw new ResourceNotFoundException("User not found");
        }
        return user;
    }

    public Region requireRegion(UUID id) {
        UUID tenantId = TenantContext.get();
        Region region = (tenantId != null)
            ? regionRepo.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Region not found"))
            : regionRepo.findById(id)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Region not found"));
        assertSameTenant(region.getTenantId());
        return region;
    }

    public Center requireCenter(UUID id) {
        UUID tenantId = TenantContext.get();
        Center center = (tenantId != null)
            ? centerRepo.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Center not found"))
            : centerRepo.findById(id)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Center not found"));
        assertSameTenant(center.getTenantId());
        return center;
    }

    public ControlRoom requireControlRoom(UUID id) {
        UUID tenantId = TenantContext.get();
        ControlRoom cr = (tenantId != null)
            ? controlRoomRepo.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("ControlRoom not found"))
            : controlRoomRepo.findById(id)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("ControlRoom not found"));
        assertSameTenant(cr.getTenantId());
        return cr;
    }

    /** Technicians may only access work orders assigned to them. */
    public void assertWorkOrderAccess(WorkOrder wo, User actor) {
        if (actor == null) return;
        if (actor.getRole() == UserRole.technician) {
            if (wo.getAssignedTo() == null
                    || !wo.getAssignedTo().getId().equals(actor.getId())) {
                throw new AccessDeniedException("Not assigned to this work order");
            }
        }
    }

    /** Technicians always see only their own assignments regardless of client filter. */
    public UUID resolveAssignedToFilter(User actor, UUID clientAssignedToId) {
        if (actor != null && actor.getRole() == UserRole.technician) {
            return actor.getId();
        }
        return clientAssignedToId;
    }
}
