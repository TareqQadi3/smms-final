package com.smms.repository;

import com.smms.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssetRepository extends JpaRepository<Asset, UUID> {
    Optional<Asset> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
}
