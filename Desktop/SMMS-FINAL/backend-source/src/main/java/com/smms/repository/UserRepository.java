package com.smms.repository;

import com.smms.entity.User;
import com.smms.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {
    Optional<User> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
    Optional<User> findByUsernameAndDeletedAtIsNull(String username);
    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    List<User>  findAllByCenterIdAndRoleAndDeletedAtIsNull(UUID centerId, UserRole role);

    boolean existsByUsernameAndDeletedAtIsNull(String username);
    boolean existsByEmailAndDeletedAtIsNull(String email);
    long countByTenantIdAndDeletedAtIsNull(UUID tenantId);
}
