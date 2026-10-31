package com.smms.repository;

import com.smms.entity.UploadRequest;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UploadRequestRepository extends JpaRepository<UploadRequest, UUID> {

    Optional<UploadRequest> findByUserIdAndIdemKey(UUID userId, String idemKey);

    /**
     * Claims the (user, key) pair. Returns 1 when this request owns it, 0 when another request
     * already did (a concurrent duplicate blocks on the unique index until the first commits).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO upload_requests (id, user_id, work_order_id, idem_key, fingerprint)"
        + " VALUES (:id, :userId, :workOrderId, :idemKey, :fingerprint)"
        + " ON CONFLICT (user_id, idem_key) DO NOTHING", nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("userId") UUID userId, @Param("workOrderId") UUID workOrderId,
              @Param("idemKey") String idemKey, @Param("fingerprint") String fingerprint);

    @Modifying
    @Query(value = "DELETE FROM upload_requests WHERE created_at < :cutoff", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
