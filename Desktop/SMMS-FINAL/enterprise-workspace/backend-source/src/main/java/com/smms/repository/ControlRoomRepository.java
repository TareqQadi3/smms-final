package com.smms.repository;

import com.smms.entity.ControlRoom;
import com.smms.enums.ControlRoomType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ControlRoomRepository extends JpaRepository<ControlRoom, UUID> {
    List<ControlRoom> findAllByCenterIdAndIsActiveTrue(UUID centerId);
    List<ControlRoom> findAllByIsActiveTrue();
    Optional<ControlRoom> findByCenterIdAndType(UUID centerId, ControlRoomType type);
}
