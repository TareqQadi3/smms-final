package com.smms.repository;

import com.smms.entity.TenantSubscription;
import com.smms.enums.TenantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantSubscriptionRepository extends JpaRepository<TenantSubscription, UUID> {
    Optional<TenantSubscription> findByTenantId(UUID tenantId);
    boolean existsByTenantId(UUID tenantId);
    List<TenantSubscription> findAllByStatusAndEndDateBefore(TenantStatus status, Instant now);
}
