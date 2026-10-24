package com.smms.repository;

import com.smms.entity.WorkOrder;
import com.smms.enums.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkOrderRepository extends JpaRepository<WorkOrder, UUID>,
        JpaSpecificationExecutor<WorkOrder> {

    Optional<WorkOrder> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
    Optional<WorkOrder> findByTicketNumberAndDeletedAtIsNull(String ticketNumber);
    long countByTenantIdAndDeletedAtIsNull(UUID tenantId);
    long countByDeletedAtIsNull();

    @Query(value = """
        SELECT * FROM work_orders
        WHERE sla_deadline < :now
          AND sla_breached = false
          AND sla_paused_at IS NULL
          AND status::text NOT IN (:excludedStatuses)
          AND deleted_at IS NULL
        """, nativeQuery = true)
    List<WorkOrder> findSlaBreached(
        @Param("now") Instant now,
        @Param("excludedStatuses") List<String> excludedStatuses);

    /**
     * Breached, still open, not paused tickets that have a level-1 escalation raised by this system
     * (legacy rows never trigger an automatic level 2) and no level 2/3 yet.
     */
    @Query(value = """
        SELECT w.* FROM work_orders w
        WHERE w.deleted_at IS NULL AND w.tenant_id IS NOT NULL
          AND w.sla_breached = true AND w.sla_paused_at IS NULL
          AND w.sla_deadline < :now
          AND w.status::text NOT IN (:excludedStatuses)
          AND EXISTS (SELECT 1 FROM work_order_escalations e
                      WHERE e.work_order_id = w.id AND e.level = 1 AND e.trigger_kind <> 'legacy_breach')
          AND NOT EXISTS (SELECT 1 FROM work_order_escalations e
                          WHERE e.work_order_id = w.id AND e.level >= 2)
        """, nativeQuery = true)
    List<WorkOrder> findLevel2Candidates(
        @Param("now") Instant now,
        @Param("excludedStatuses") List<String> excludedStatuses);

    @Query("SELECT COUNT(w) FROM WorkOrder w WHERE w.controlRoom.id = :crId " +
           "AND w.status NOT IN :excluded AND w.deletedAt IS NULL")
    long countActiveByControlRoom(
        @Param("crId") UUID crId,
        @Param("excluded") List<TicketStatus> excluded);

    @Modifying
    @Query("UPDATE WorkOrder w SET w.slaBreached = true WHERE w.id IN :ids")
    void markSlaBreached(@Param("ids") List<UUID> ids);
}
