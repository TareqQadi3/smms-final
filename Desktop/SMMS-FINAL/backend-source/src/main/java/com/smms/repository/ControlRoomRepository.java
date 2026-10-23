package com.smms.repository;

import com.smms.entity.ControlRoom;
import com.smms.enums.ControlRoomType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ControlRoomRepository extends JpaRepository<ControlRoom, UUID> {
    Optional<ControlRoom> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
    List<ControlRoom> findAllByCenterIdAndIsActiveTrue(UUID centerId);
    List<ControlRoom> findAllByIsActiveTrue();
    List<ControlRoom> findAllByTenantIdAndIsActiveTrue(UUID tenantId);
    List<ControlRoom> findAllByTenantIdAndCenterIdAndIsActiveTrue(UUID tenantId, UUID centerId);
    /** Rooms of every center of one region (region_supervisor listing). */
    List<ControlRoom> findAllByTenantIdAndCenterRegionIdAndIsActiveTrue(UUID tenantId, UUID regionId);
    List<ControlRoom> findAllByCenterRegionIdAndIsActiveTrue(UUID regionId);
    Optional<ControlRoom> findByCenterIdAndType(UUID centerId, ControlRoomType type);
    List<ControlRoom> findAllByIdInAndIsActiveTrue(Collection<UUID> ids);
}
