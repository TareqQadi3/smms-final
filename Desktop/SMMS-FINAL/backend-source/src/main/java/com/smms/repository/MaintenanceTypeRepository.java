package com.smms.repository;

import com.smms.entity.MaintenanceType;
import com.smms.enums.ControlRoomType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MaintenanceTypeRepository extends JpaRepository<MaintenanceType, UUID> {
    List<MaintenanceType> findAllByIsActiveTrueOrderByNameAsc();
    List<MaintenanceType> findAllByTenantIdAndIsActiveTrueOrderByNameAsc(UUID tenantId);
    List<MaintenanceType> findAllByControlRoomTypeAndIsActiveTrueOrderByNameAsc(ControlRoomType type);
    List<MaintenanceType> findAllByTenantIdAndControlRoomTypeAndIsActiveTrueOrderByNameAsc(UUID tenantId, ControlRoomType type);
    java.util.Optional<MaintenanceType> findByIdAndTenantId(UUID id, UUID tenantId);
    boolean existsByNameAndControlRoomType(String name, ControlRoomType type);
}
