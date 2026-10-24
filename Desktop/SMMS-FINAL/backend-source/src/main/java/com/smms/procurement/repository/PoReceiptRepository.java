package com.smms.procurement.repository;

import com.smms.procurement.entity.PoReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PoReceiptRepository extends JpaRepository<PoReceipt, UUID> {
    List<PoReceipt> findByPoIdOrderByReceivedAtAsc(UUID poId);
}
