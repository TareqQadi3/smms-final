package com.smms.repository;

import com.smms.entity.AuthSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    /** Row lock: two concurrent refreshes of one session are serialised here. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.id = :id")
    Optional<AuthSession> findByIdForUpdate(@Param("id") UUID id);

    @Query("select count(s) from AuthSession s where s.id = :id and s.userId = :userId"
         + " and s.revokedAt is null and s.expiresAt > :now")
    long countActive(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AuthSession s set s.revokedAt = :now, s.revokeReason = :reason"
         + " where s.userId = :userId and s.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now,
                         @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AuthSession s set s.revokedAt = :now, s.revokeReason = :reason"
         + " where s.userId = :userId and s.revokedAt is null and s.id <> :keep")
    int revokeOthersForUser(@Param("userId") UUID userId, @Param("keep") UUID keep,
                            @Param("now") Instant now, @Param("reason") String reason);

    @Modifying
    @Query("delete from AuthSession s where s.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
