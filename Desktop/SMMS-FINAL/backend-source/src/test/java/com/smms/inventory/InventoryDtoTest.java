package com.smms.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.dto.request.CreateInventoryItemRequest;
import com.smms.inventory.dto.Dtos.ReceiptRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryDtoTest {

    // same configuration as Spring MVC (unknown properties are ignored unless a class says otherwise)
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test void initialQuantityIsRejected() {
        String json = "{\"centerId\":\"" + UUID.randomUUID() + "\",\"name\":\"n\",\"nameAr\":\"n\",\"sku\":\"S-1\",\"quantity\":50}";
        assertThatThrownBy(() -> mapper.readValue(json, CreateInventoryItemRequest.class)).hasMessageContaining("quantity");
    }

    @Test void itemRequestValidation() throws Exception {
        String ok = "{\"centerId\":\"" + UUID.randomUUID() + "\",\"name\":\"n\",\"nameAr\":\"n\",\"sku\":\"S-1\",\"reorderLevel\":3.5}";
        assertThat(validator.validate(mapper.readValue(ok, CreateInventoryItemRequest.class))).isEmpty();
        String bad = "{\"centerId\":\"" + UUID.randomUUID() + "\",\"name\":\"n\",\"nameAr\":\"n\",\"sku\":\"bad sku!\",\"reorderLevel\":-1}";
        assertThat(validator.validate(mapper.readValue(bad, CreateInventoryItemRequest.class))).hasSize(2);
    }

    @Test void receiptNeedsLinesAndPositiveQuantities() throws Exception {
        UUID wh = UUID.randomUUID(), it = UUID.randomUUID();
        assertThat(validator.validate(mapper.readValue("{\"warehouseId\":\"" + wh + "\",\"lines\":[]}", ReceiptRequest.class))).isNotEmpty();
        assertThat(validator.validate(mapper.readValue("{\"warehouseId\":\"" + wh + "\",\"lines\":[{\"itemId\":\"" + it + "\",\"quantity\":0}]}", ReceiptRequest.class))).isNotEmpty();
        assertThat(validator.validate(mapper.readValue("{\"warehouseId\":\"" + wh + "\",\"lines\":[{\"itemId\":\"" + it + "\",\"quantity\":1.5}]}", ReceiptRequest.class))).isEmpty();
    }
}
