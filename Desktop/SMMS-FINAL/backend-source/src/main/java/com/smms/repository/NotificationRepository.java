package com.smms.repository;

import com.smms.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    Page<Notification> findByUserId(UUID userId, Pageable pageable);
    long countByUserIdAndReadFalse(UUID userId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.user.id = :userId AND n.read = false")
    void markAllReadByUser(@Param("userId") UUID userId);

    /** Marks one notification read, only when it belongs to the user. Returns rows changed (0 or 1). */
    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.id = :id AND n.user.id = :userId")
    int markReadByIdAndUser(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.id = :id AND n.user.id = :userId")
    long countByIdAndUser(@Param("id") UUID id, @Param("userId") UUID userId);
}
