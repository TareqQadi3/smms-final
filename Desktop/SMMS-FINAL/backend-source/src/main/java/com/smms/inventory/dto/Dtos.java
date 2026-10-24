package com.smms.inventory.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smms.inventory.api.StockLedger.MovementKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes of the inventory API. */
public final class Dtos {
    private Dtos() {}

    // ── requests ─────────────────────────────────────────────

    public record UomRequest(
        @NotBlank @Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_./-]*$", message = "letters, digits, _ . / - only") String code,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 100) String nameAr,
        @NotNull @Min(0) @Max(3) Integer decimals,
        Boolean active,
        /** Platform admin only (no tenant context); ignored for tenant users. */
        UUID tenantId) {}

    public record WarehouseRequest(
        @NotNull UUID centerId,
        @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]*$", message = "letters, digits, _ - only") String code,
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 200) String nameAr,
        Boolean active) {}

    public record ReceiptLine(@NotNull UUID itemId,
                              @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 7, fraction = 3) BigDecimal quantity) {}

    public record ReceiptRequest(@NotNull UUID warehouseId, @Size(max = 120) String reference, @Size(max = 1000) String note,
                                 @NotNull @Size(min = 1, max = 50) List<@Valid @NotNull ReceiptLine> lines) {}

    public record AdjustmentRequest(@NotNull UUID warehouseId, @NotNull UUID itemId,
                                    @NotNull @Pattern(regexp = "^(in|out)$", message = "must be in or out") String direction,
                                    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 7, fraction = 3) BigDecimal quantity,
                                    @NotNull @Pattern(regexp = "^(count_correction|damage|expired|found|other)$",
                                        message = "must be count_correction, damage, expired, found or other") String reasonCode,
                                    @NotNull @Size(min = 5, max = 1000) String note) {}

    public record ReverseRequest(@NotNull @Size(min = 5, max = 1000) String reason) {}

    public record MinQtyRequest(@NotNull UUID warehouseId, @NotNull UUID itemId,
                                @DecimalMin("0") @Digits(integer = 7, fraction = 3) BigDecimal minQty) {}

    // ── responses ────────────────────────────────────────────

    public record UomResponse(UUID id, String code, String name, String nameAr, int decimals, boolean active) {}

    public record WarehouseResponse(UUID id, UUID centerId, String centerName, String code, String name, String nameAr, boolean active) {}

    public record UserRef(UUID id, String fullName, String fullNameAr) {}

    public record ItemRefView(UUID id, String sku, String name, String nameAr, String uom) {}

    public record WarehouseRefView(UUID id, String code, String name, String nameAr) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MovementResponse(UUID id, MovementKind kind, ItemRefView item, WarehouseRefView warehouse,
                                   BigDecimal quantity, BigDecimal balanceAfter, UUID workOrderId, String ticketNumber,
                                   UUID materialRequestId, UUID poId, UUID reversalOfId, UUID reversedById,
                                   String reasonCode, String note, String reference, UserRef actor, Instant createdAt) {}

    public record ReceiptResponse(List<MovementResponse> movements) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BalanceResponse(UUID id, ItemRefView item, WarehouseRefView warehouse, UUID centerId,
                                  BigDecimal onHand, BigDecimal reserved, BigDecimal available, BigDecimal minQty, boolean belowMin) {}

    public record StockCardResponse(ItemRefView item, WarehouseRefView warehouse, LocalDate from, LocalDate to,
                                    BigDecimal openingBalance, BigDecimal closingBalance,
                                    org.springframework.data.domain.Page<MovementResponse> entries) {}
}
