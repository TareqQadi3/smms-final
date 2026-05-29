package com.smms.repository;

import com.smms.entity.UserControlRoomAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserControlRoomAccessRepository extends JpaRepository<UserControlRoomAccess, UUID> {
    List<UserControlRoomAccess> findByUserId(UUID userId);
    List<UserControlRoomAccess> findByControlRoomId(UUID controlRoomId);
    boolean existsByUserIdAndControlRoomId(UUID userId, UUID controlRoomId);
    void deleteByUserIdAndControlRoomId(UUID userId, UUID controlRoomId);
}
