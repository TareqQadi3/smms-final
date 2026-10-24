package com.smms.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InfoRequestRepository extends JpaRepository<InfoRequest, UUID> {

    @Query("SELECT r FROM InfoRequest r WHERE r.workOrderId = :wo AND r.answeredAt IS NULL AND r.cancelledAt IS NULL")
    Optional<InfoRequest> findOpen(@Param("wo") UUID workOrderId);

    @Query("SELECT r FROM InfoRequest r JOIN FETCH r.requestedBy LEFT JOIN FETCH r.answeredBy "
        + "WHERE r.workOrderId = :wo ORDER BY r.requestedAt ASC")
    List<InfoRequest> findAllForWorkOrder(@Param("wo") UUID workOrderId);
}
