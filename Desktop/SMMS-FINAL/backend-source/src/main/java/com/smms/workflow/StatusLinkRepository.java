package com.smms.workflow;

import com.smms.entity.WorkOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface StatusLinkRepository extends JpaRepository<StatusLink, UUID> {

    @org.springframework.transaction.annotation.Transactional
    @Modifying(flushAutomatically = true)
    @Query("UPDATE StatusLink l SET l.revokedAt = :now WHERE l.workOrderId = :wo AND l.revokedAt IS NULL")
    int revokeActive(@Param("wo") UUID workOrderId, @Param("now") Instant now);

    /** The work order behind an active (not revoked, not expired) link; deleted work orders never match. */
    @Query("SELECT w FROM StatusLink l, WorkOrder w WHERE l.tokenHash = :hash AND l.revokedAt IS NULL "
        + "AND l.expiresAt > :now AND w.id = l.workOrderId AND w.deletedAt IS NULL")
    Optional<WorkOrder> findWorkOrderByActiveToken(@Param("hash") String tokenHash, @Param("now") Instant now);
}
