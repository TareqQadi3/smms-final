package com.smms.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface EscalationRepository extends JpaRepository<WorkOrderEscalation, UUID> {

    /**
     * Insert-first duplicate guard: returns 1 when the row was inserted and 0 when (work order, level)
     * already exists. Only the caller that gets 1 may notify.
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = "INSERT INTO work_order_escalations "
        + "(id, tenant_id, work_order_id, level, trigger_kind, triggered_by_id, reason, notified_count, created_at) "
        + "VALUES (:id, :tenant, :wo, :level, :kind, :by, :reason, NULL, now()) "
        + "ON CONFLICT (work_order_id, level) DO NOTHING", nativeQuery = true)
    int insertIgnore(@Param("id") UUID id, @Param("tenant") UUID tenantId, @Param("wo") UUID workOrderId,
                     @Param("level") int level, @Param("kind") String kind, @Param("by") UUID triggeredById,
                     @Param("reason") String reason);

    @org.springframework.transaction.annotation.Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE work_order_escalations SET notified_count = :n WHERE id = :id", nativeQuery = true)
    int setNotified(@Param("id") UUID id, @Param("n") int notified);

    @Query("SELECT MAX(e.level) FROM WorkOrderEscalation e WHERE e.workOrderId = :wo")
    Integer maxLevel(@Param("wo") UUID workOrderId);

    @Query("SELECT e.workOrderId, MAX(e.level) FROM WorkOrderEscalation e WHERE e.workOrderId IN :ids GROUP BY e.workOrderId")
    List<Object[]> maxLevels(@Param("ids") Collection<UUID> workOrderIds);

    @Query("SELECT e FROM WorkOrderEscalation e LEFT JOIN FETCH e.triggeredBy WHERE e.workOrderId = :wo ORDER BY e.level ASC")
    List<WorkOrderEscalation> findAllForWorkOrder(@Param("wo") UUID workOrderId);
}
