package com.smms.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/** New catalog item. Unknown fields (for example an initial {@code quantity}) are rejected: stock enters only through receipts. */
@Data
public class CreateInventoryItemRequest {
    @NotNull private UUID centerId;
    @NotBlank @Size(max = 200) private String name;
    @NotBlank @Size(max = 200) private String nameAr;
    @NotBlank @Size(max = 80) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._/-]*$", message = "letters, digits, . _ / - only") private String sku;
    @Size(max = 80) private String category;
    private UUID uomId;
    @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2) private BigDecimal reorderLevel;

    /** Unknown fields (for example an initial {@code quantity}) are a 400, not silently ignored. */
    @JsonAnySetter
    void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }
}
