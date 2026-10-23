package com.smms.repository;

import com.smms.entity.UserControlRoomAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserControlRoomAccessRepository extends JpaRepository<UserControlRoomAccess, UUID> {
    List<UserControlRoomAccess> findByUserId(UUID userId);
    List<UserControlRoomAccess> findByControlRoomId(UUID controlRoomId);
    boolean existsByUserIdAndControlRoomId(UUID userId, UUID controlRoomId);
    /** Id-only projection (no entity hydration) used by the work-order access policy. */
    @Query("select a.controlRoom.id from UserControlRoomAccess a where a.user.id = :userId")
    List<UUID> findControlRoomIdsByUserId(@Param("userId") UUID userId);
    void deleteByUserIdAndControlRoomId(UUID userId, UUID controlRoomId);
}
