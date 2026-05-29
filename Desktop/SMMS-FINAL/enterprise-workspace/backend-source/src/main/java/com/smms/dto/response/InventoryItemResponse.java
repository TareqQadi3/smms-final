package com.smms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data @Builder
public class InventoryItemResponse {
    private UUID id;
    private String name;
    private String nameAr;
    private String sku;
    private String unit;
    private BigDecimal quantity;
    private BigDecimal reorderLevel;
    private boolean lowStock;
    private CenterSummary center;
}

