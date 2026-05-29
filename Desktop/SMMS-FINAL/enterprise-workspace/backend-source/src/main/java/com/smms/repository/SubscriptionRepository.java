package com.smms.repository;

import com.smms.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByCenterIdAndDeletedAtIsNull(UUID centerId);

    List<Subscription> findAllByDeletedAtIsNull();

    boolean existsByCenterIdAndDeletedAtIsNull(UUID centerId);
}
