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

    Optional<WorkOrder> findByTicketNumberAndDeletedAtIsNull(String ticketNumber);

    @Query(value = """
        SELECT * FROM work_orders
        WHERE sla_deadline < :now
          AND sla_breached = false
          AND status::text NOT IN (:excludedStatuses)
          AND deleted_at IS NULL
        """, nativeQuery = true)
    List<WorkOrder> findSlaBreached(
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
