package com.smms.assets;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssetDowntimeRepository extends JpaRepository<AssetDowntime, UUID> {

    Page<AssetDowntime> findByAssetIdAndTenantId(UUID assetId, UUID tenantId, Pageable pageable);

    Optional<AssetDowntime> findByIdAndAssetId(UUID id, UUID assetId);

    Optional<AssetDowntime> findByAssetIdAndEndedAtIsNull(UUID assetId);

    @Query("SELECT d.assetId FROM AssetDowntime d WHERE d.assetId IN :ids AND d.endedAt IS NULL")
    List<UUID> findOpenAssetIds(@Param("ids") Collection<UUID> ids);

    /** Does any existing period of the asset overlap [start, end)? Open periods overlap everything after their start. */
    @Query("SELECT COUNT(d) > 0 FROM AssetDowntime d WHERE d.assetId = :assetId "
        + "AND (d.endedAt IS NULL OR d.endedAt > :start) AND d.startedAt < :end")
    boolean existsOverlap(@Param("assetId") UUID assetId, @Param("start") Instant start, @Param("end") Instant end);
}
