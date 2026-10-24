package com.smms.pm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MaintenancePlanRepository extends JpaRepository<MaintenancePlan, UUID>,
        JpaSpecificationExecutor<MaintenancePlan> {

    Optional<MaintenancePlan> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    /** Row lock for generation; a plan another transaction holds is skipped (empty result). */
    @Query(value = "SELECT * FROM maintenance_plans WHERE id = :id AND tenant_id = :tenantId AND deleted_at IS NULL "
        + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<MaintenancePlan> lockForGeneration(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    /** Same lock but waits (manual generate-now: the caller wants a definite answer). */
    @Query(value = "SELECT * FROM maintenance_plans WHERE id = :id AND tenant_id = :tenantId AND deleted_at IS NULL "
        + "FOR UPDATE", nativeQuery = true)
    Optional<MaintenancePlan> lockWaiting(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    /** Plans of active tenants that reached their lead window; (plan id, tenant id) pairs. */
    @Query(value = "SELECT CAST(p.id AS text), CAST(p.tenant_id AS text) FROM maintenance_plans p "
        + "JOIN tenants t ON t.id = p.tenant_id "
        + "WHERE p.is_active AND p.deleted_at IS NULL AND COALESCE(t.is_active, TRUE) "
        + "AND p.next_due_date - p.lead_days <= :today ORDER BY p.next_due_date, p.id LIMIT :lim", nativeQuery = true)
    List<Object[]> findDueRefs(@Param("today") LocalDate today, @Param("lim") int limit);

    /** Pauses every live plan of a (decommissioned) asset; returns how many changed. */
    @Modifying
    @Query("UPDATE MaintenancePlan p SET p.active = false WHERE p.asset.id = :assetId AND p.tenantId = :tenantId "
        + "AND p.deletedAt IS NULL AND p.active = true")
    int deactivateByAsset(@Param("assetId") UUID assetId, @Param("tenantId") UUID tenantId);
}
