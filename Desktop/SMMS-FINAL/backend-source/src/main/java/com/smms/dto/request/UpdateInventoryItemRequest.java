package com.smms.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/** Catalog update: centerId and sku are immutable. */
@Data
public class UpdateInventoryItemRequest {
    @NotBlank @Size(max = 200) private String name;
    @NotBlank @Size(max = 200) private String nameAr;
    @Size(max = 80) private String category;
    private UUID uomId;
    @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2) private BigDecimal reorderLevel;
    private Boolean active;

    /** Unknown fields (for example an initial {@code quantity}) are a 400, not silently ignored. */
    @JsonAnySetter
    void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }
}
