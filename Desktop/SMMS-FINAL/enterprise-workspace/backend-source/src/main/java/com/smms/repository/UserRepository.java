package com.smms.repository;

import com.smms.entity.User;
import com.smms.enums.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsernameAndDeletedAtIsNull(String username);
    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    // Filtered queries for list()
    Page<User> findAllByDeletedAtIsNull(Pageable pageable);
    Page<User> findAllByCenterIdAndDeletedAtIsNull(UUID centerId, Pageable pageable);
    Page<User> findAllByRoleAndDeletedAtIsNull(UserRole role, Pageable pageable);
    Page<User> findAllByCenterIdAndRoleAndDeletedAtIsNull(UUID centerId, UserRole role, Pageable pageable);

    // Non-paginated for access control checks
    List<User> findAllByCenterIdAndRoleAndDeletedAtIsNull(UUID centerId, UserRole role);

    boolean existsByUsernameAndDeletedAtIsNull(String username);
    boolean existsByEmailAndDeletedAtIsNull(String email);
}
