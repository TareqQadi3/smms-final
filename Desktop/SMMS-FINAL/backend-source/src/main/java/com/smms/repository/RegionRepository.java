package com.smms.repository;

import com.smms.entity.Region;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RegionRepository extends JpaRepository<Region, UUID> {
    Optional<Region> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
    Optional<Region> findByCodeAndDeletedAtIsNull(String code);
    List<Region> findAllByIsActiveTrueAndDeletedAtIsNull();
    List<Region> findAllByTenantIdAndIsActiveTrueAndDeletedAtIsNull(UUID tenantId);
}
