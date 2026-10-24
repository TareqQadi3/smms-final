package com.smms.inventory.repo;

import com.smms.entity.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID>, JpaSpecificationExecutor<InventoryItem> {
    boolean existsByCenterIdAndSkuIgnoreCase(UUID centerId, String sku);

    @Query("select (count(i) > 0) from InventoryItem i where i.uom.id = :uomId")
    boolean existsByUom(@Param("uomId") UUID uomId);

    /**
     * Atomic claim of the low-stock notification: true (1 row) for exactly one caller while the
     * item is at or below its reorder level and not yet notified.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE inventory_items i SET low_stock_notified_at = now() WHERE i.id = :id "
        + "AND i.is_active AND i.reorder_level > 0 AND i.low_stock_notified_at IS NULL "
        + "AND COALESCE((SELECT sum(b.on_hand) FROM stock_balances b WHERE b.item_id = i.id), 0) <= i.reorder_level",
        nativeQuery = true)
    int claimLowStock(@Param("id") UUID id);

    /** Re-arms the alert once stock is above the reorder level again. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE inventory_items i SET low_stock_notified_at = NULL WHERE i.id = :id "
        + "AND i.low_stock_notified_at IS NOT NULL "
        + "AND COALESCE((SELECT sum(b.on_hand) FROM stock_balances b WHERE b.item_id = i.id), 0) > i.reorder_level",
        nativeQuery = true)
    int rearmLowStock(@Param("id") UUID id);
}
