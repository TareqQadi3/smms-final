package com.smms.inventory.repo;

import com.smms.inventory.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID>, JpaSpecificationExecutor<Warehouse> {
    boolean existsByCenterIdAndCodeIgnoreCase(UUID centerId, String code);
    boolean existsByCenterIdAndCodeIgnoreCaseAndIdNot(UUID centerId, String code, UUID id);
}
