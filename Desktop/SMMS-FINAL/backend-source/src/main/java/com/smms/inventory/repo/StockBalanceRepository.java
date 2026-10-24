package com.smms.inventory.repo;

import com.smms.inventory.entity.StockBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockBalanceRepository extends JpaRepository<StockBalance, UUID>, JpaSpecificationExecutor<StockBalance> {

    /** Row lock (SELECT ... FOR UPDATE); callers acquire locks in (item, warehouse) sorted order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from StockBalance b where b.item.id = :itemId and b.warehouse.id = :warehouseId")
    Optional<StockBalance> lockByItemAndWarehouse(@Param("itemId") UUID itemId, @Param("warehouseId") UUID warehouseId);

    @Query("select b from StockBalance b join fetch b.warehouse where b.item.id = :itemId order by b.warehouse.code")
    List<StockBalance> findByItem(@Param("itemId") UUID itemId);

    Optional<StockBalance> findByItemIdAndWarehouseId(UUID itemId, UUID warehouseId);

    /** [itemId, sum(on_hand), sum(reserved)] for the given items. */
    @Query("select b.item.id, sum(b.onHand), sum(b.reserved) from StockBalance b where b.item.id in :ids group by b.item.id")
    List<Object[]> totals(@Param("ids") java.util.Collection<UUID> ids);

    @Query("select coalesce(sum(b.onHand), 0) from StockBalance b where b.item.id = :itemId")
    BigDecimal totalOnHand(@Param("itemId") UUID itemId);
}
