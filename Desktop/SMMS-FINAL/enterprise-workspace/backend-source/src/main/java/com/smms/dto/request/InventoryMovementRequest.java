package com.smms.dto.request;

import com.smms.enums.InventoryMovementType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class InventoryMovementRequest {
    @NotNull private InventoryMovementType type;
    @NotNull private BigDecimal quantity;
    private UUID workOrderId;
    private String note;
}

