package com.smms.repository;

import com.smms.entity.TicketHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TicketHistoryRepository extends JpaRepository<TicketHistory, UUID> {

    @Query("SELECT h FROM TicketHistory h LEFT JOIN FETCH h.actor WHERE h.workOrder.id = :workOrderId ORDER BY h.createdAt ASC")
    List<TicketHistory> findByWorkOrderIdOrderByCreatedAtAsc(@Param("workOrderId") UUID workOrderId);
}
