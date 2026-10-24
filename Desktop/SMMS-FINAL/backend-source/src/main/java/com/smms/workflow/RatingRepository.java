package com.smms.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RatingRepository extends JpaRepository<WorkOrderRating, UUID> {
    Optional<WorkOrderRating> findByWorkOrderId(UUID workOrderId);
    boolean existsByWorkOrderId(UUID workOrderId);
}
