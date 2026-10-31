package com.smms.department;

import com.smms.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/** Paged, filtered department queries (the basic finders stay in {@code repository.DepartmentRepository}). */
public interface DepartmentCrudRepository extends JpaRepository<Department, UUID>, JpaSpecificationExecutor<Department> {
    /** Includes soft-deleted rows: the (center, code) unique constraint does not ignore them. */
    boolean existsByCenterIdAndCodeIgnoreCase(UUID centerId, String code);
    boolean existsByCenterIdAndCodeIgnoreCaseAndIdNot(UUID centerId, String code, UUID id);
}
