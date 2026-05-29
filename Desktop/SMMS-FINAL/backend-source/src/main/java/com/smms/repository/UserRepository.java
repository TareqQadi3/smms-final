package com.smms.repository;

import com.smms.entity.User;
import com.smms.enums.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
    Optional<User> findByUsernameAndDeletedAtIsNull(String username);
    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    // Standard filtered queries
    Page<User> findAllByDeletedAtIsNull(Pageable pageable);
    Page<User> findAllByCenterIdAndDeletedAtIsNull(UUID centerId, Pageable pageable);
    Page<User> findAllByRoleAndDeletedAtIsNull(UserRole role, Pageable pageable);
    Page<User> findAllByCenterIdAndRoleAndDeletedAtIsNull(UUID centerId, UserRole role, Pageable pageable);
    List<User>  findAllByCenterIdAndRoleAndDeletedAtIsNull(UUID centerId, UserRole role);

    // Standard tenant-scoped list (system_admin passes null tenantId to bypass)
    Page<User> findAllByTenantIdAndDeletedAtIsNull(UUID tenantId, Pageable pageable);
    Page<User> findAllByTenantIdAndRoleAndDeletedAtIsNull(UUID tenantId, UserRole role, Pageable pageable);
    Page<User> findAllByTenantIdAndCenterIdAndDeletedAtIsNull(UUID tenantId, UUID centerId, Pageable pageable);
    Page<User> findAllByTenantIdAndCenterIdAndRoleAndDeletedAtIsNull(UUID tenantId, UUID centerId, UserRole role, Pageable pageable);

    // ISSUE 1 FIX: Region-scoped query — excludes roles the region_supervisor must not see
    @Query("""
        SELECT u FROM User u
        WHERE u.deletedAt IS NULL
          AND u.role NOT IN :excluded
          AND (u.tenantId = :tenantId OR :tenantId IS NULL)
          AND (
            u.region.id = :regionId
            OR (u.center IS NOT NULL AND u.center.region.id = :regionId)
          )
        """)
    Page<User> findAllInRegionExcludingRoles(
        @Param("regionId")  UUID regionId,
        @Param("excluded")  Collection<UserRole> excluded,
        @Param("tenantId")  UUID tenantId,
        Pageable pageable);

    boolean existsByUsernameAndDeletedAtIsNull(String username);
    boolean existsByEmailAndDeletedAtIsNull(String email);
    long countByTenantIdAndDeletedAtIsNull(UUID tenantId);
}
