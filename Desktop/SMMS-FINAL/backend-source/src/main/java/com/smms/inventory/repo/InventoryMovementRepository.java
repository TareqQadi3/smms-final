package com.smms.inventory.repo;

import com.smms.entity.InventoryMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, UUID>, JpaSpecificationExecutor<InventoryMovement> {

    boolean existsByReversalOfId(UUID reversalOfId);

    boolean existsByItemId(UUID itemId);

    @Query("select m from InventoryMovement m join fetch m.item left join fetch m.warehouse where m.idempotencyRecordId = :rec order by m.seq")
    List<InventoryMovement> findByIdempotencyRecord(@Param("rec") UUID rec);

    /** [reversalOfId, id] pairs: which of the given movements were reversed, and by what. */
    @Query("select m.reversalOfId, m.id from InventoryMovement m where m.reversalOfId in :ids")
    List<Object[]> reversalsOf(@Param("ids") Collection<UUID> ids);

    @Query("select (count(m) > 0) from InventoryMovement m where m.item.uom.id = :uomId")
    boolean existsByUom(@Param("uomId") UUID uomId);

    /** Latest ledger balance of a warehouse/item strictly before an instant (stock card opening). */
    @Query("select m.balanceAfter from InventoryMovement m where m.item.id = :itemId and m.warehouse.id = :whId "
        + "and m.createdAt < :before and m.movementKind is not null order by m.seq desc limit 1")
    java.util.Optional<java.math.BigDecimal> balanceBefore(@Param("itemId") UUID itemId, @Param("whId") UUID whId,
                                                          @Param("before") java.time.Instant before);
}
