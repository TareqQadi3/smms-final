package com.smms.procurement.dto;

import com.smms.inventory.api.StockLedger.ItemRef;
import com.smms.inventory.api.StockLedger.WarehouseRef;
import com.smms.procurement.dto.Refs.CenterRef;
import com.smms.procurement.entity.PurchaseOrderStatus;
import com.smms.procurement.dto.Refs.SupplierRef;
import com.smms.procurement.dto.Refs.UserRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PurchaseOrderDtos {
    private PurchaseOrderDtos() {}

    public record PoLineRequest(
        @NotNull UUID itemId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000") @Digits(integer = 7, fraction = 3)
        BigDecimal qtyOrdered,
        @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal unitPrice) {}

    /** {@code version} is used by PUT only (409 on mismatch). */
    public record PurchaseOrderRequest(
        @NotNull UUID centerId, @NotNull UUID supplierId, @NotNull UUID warehouseId,
        LocalDate expectedDate, @Size(max = 2000) String notes, UUID workOrderId, UUID materialRequestId,
        @NotEmpty @Size(max = 100) List<@Valid @NotNull PoLineRequest> lines, Long version) {}

    public record NoteRequest(@Size(max = 1000) String note, Long version) {}

    public record ReasonRequest(@NotBlank @Size(min = 5, max = 1000) String reason, Long version) {}

    public record ReceiveLineRequest(
        @NotNull UUID poLineId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000") @Digits(integer = 7, fraction = 3)
        BigDecimal quantity) {}

    public record ReceiveRequest(@Size(max = 1000) String note,
                                 @NotEmpty @Size(max = 100) List<@Valid @NotNull ReceiveLineRequest> lines) {}

    public record PoLineResponse(UUID id, int lineNo, ItemRef item, BigDecimal qtyOrdered, BigDecimal qtyReceived,
                                 BigDecimal unitPrice) {}

    public record ReceiptLineResponse(UUID poLineId, BigDecimal quantity, UUID movementId) {}

    public record ReceiptResponse(UUID id, UUID poId, Instant receivedAt, UserRef receivedBy, String note,
                                  List<ReceiptLineResponse> lines) {}

    public record PurchaseOrderSummary(UUID id, String poNumber, PurchaseOrderStatus status, CenterRef center,
                                       SupplierRef supplier, UUID warehouseId, LocalDate expectedDate,
                                       BigDecimal total, Instant createdAt) {}

    public record PurchaseOrderResponse(UUID id, String poNumber, PurchaseOrderStatus status, CenterRef center,
                                        SupplierRef supplier, WarehouseRef warehouse, UUID workOrderId,
                                        UUID materialRequestId, LocalDate expectedDate, String notes,
                                        UserRef createdBy, Instant submittedAt, UserRef approvedBy,
                                        Instant approvedAt, String decisionReason, BigDecimal total,
                                        List<PoLineResponse> lines, List<ReceiptResponse> receipts, Long version) {}
}
