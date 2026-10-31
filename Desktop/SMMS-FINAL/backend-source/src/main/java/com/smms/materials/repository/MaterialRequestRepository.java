package com.smms.materials.repository;

import com.smms.materials.entity.MaterialRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MaterialRequestRepository extends JpaRepository<MaterialRequest, UUID>, JpaSpecificationExecutor<MaterialRequest> {
}
