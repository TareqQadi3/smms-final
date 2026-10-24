package com.smms.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Catalog row. Stock figures are null (omitted) for roles that may not read balances. */
@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InventoryItemResponse {
    private UUID id;
    private String sku;
    private String name;
    private String nameAr;
    private String category;
    private String uom;
    private UUID uomId;
    private BigDecimal reorderLevel;
    private BigDecimal totalOnHand;
    private BigDecimal totalReserved;
    private BigDecimal totalAvailable;
    private Boolean lowStock;
    private boolean active;
    private CenterSummary center;
    /** Detail only. */
    private List<BalanceLine> balances;

    @Data @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BalanceLine {
        private WarehouseView warehouse;
        private BigDecimal onHand;
        private BigDecimal reserved;
        private BigDecimal available;
        private BigDecimal minQty;
    }

    public record WarehouseView(UUID id, String code, String name, String nameAr) {}
}
