package com.smms.repository;

import com.smms.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, UUID> {
    Optional<Department> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
}
