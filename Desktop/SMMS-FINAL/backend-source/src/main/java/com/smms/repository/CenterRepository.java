package com.smms.repository;

import com.smms.entity.Center;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CenterRepository extends JpaRepository<Center, UUID> {
    Optional<Center> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
    List<Center> findAllByRegionIdAndDeletedAtIsNull(UUID regionId);
    List<Center> findAllByDeletedAtIsNull();
    Optional<Center> findByCodeAndDeletedAtIsNull(String code);
    List<Center> findAllByTenantIdAndDeletedAtIsNull(UUID tenantId);
    List<Center> findAllByTenantIdAndRegionIdAndDeletedAtIsNull(UUID tenantId, UUID regionId);
    long countByTenantIdAndDeletedAtIsNull(UUID tenantId);
}
