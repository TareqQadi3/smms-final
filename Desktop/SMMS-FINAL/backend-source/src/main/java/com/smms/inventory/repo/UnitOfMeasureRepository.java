package com.smms.inventory.repo;

import com.smms.inventory.entity.UnitOfMeasure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface UnitOfMeasureRepository extends JpaRepository<UnitOfMeasure, UUID>, JpaSpecificationExecutor<UnitOfMeasure> {
    boolean existsByTenantIdAndCodeIgnoreCase(UUID tenantId, String code);
    boolean existsByTenantIdAndCodeIgnoreCaseAndIdNot(UUID tenantId, String code, UUID id);
}
