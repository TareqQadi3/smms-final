package com.smms.repository;

import com.smms.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssetRepository extends JpaRepository<Asset, UUID>, JpaSpecificationExecutor<Asset> {
    Optional<Asset> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    /** Live asset with the same code in the center (the partial unique index is the backstop). */
    @Query("SELECT a FROM Asset a WHERE a.center.id = :centerId AND a.assetCode = :code AND a.deletedAt IS NULL")
    Optional<Asset> findLiveByCenterAndCode(@Param("centerId") UUID centerId, @Param("code") String code);
}
