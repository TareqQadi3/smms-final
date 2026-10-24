package com.smms.materials.dto;

import com.smms.enums.TicketStatus;
import com.smms.inventory.api.StockLedger.ItemRef;
import com.smms.inventory.api.StockLedger.WarehouseRef;
import com.smms.materials.entity.MaterialRequestStatus;
import com.smms.procurement.dto.Refs.CenterRef;
import com.smms.procurement.dto.Refs.UserRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MaterialRequestDtos {
    private MaterialRequestDtos() {}

    public record CreateLine(
        @NotNull UUID itemId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000") @Digits(integer = 7, fraction = 3)
        BigDecimal quantity) {}

    public record CreateRequest(@NotNull UUID warehouseId, @Size(max = 1000) String note,
                                @NotEmpty @Size(max = 30) List<@Valid @NotNull CreateLine> lines) {}

    public record ApproveLine(@NotNull UUID lineId,
                              @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3)
                              BigDecimal approvedQuantity) {}

    /** lines omitted = every line approved at the requested quantity; lines given = unlisted lines get 0. */
    public record ApproveRequest(@Size(max = 1000) String note, @Size(max = 30) List<@Valid @NotNull ApproveLine> lines,
                                 Long version) {}

    public record ReasonRequest(@NotBlank @Size(min = 5, max = 1000) String reason, Long version) {}

    public record VersionRequest(Long version) {}

    public record LineQuantity(@NotNull UUID lineId,
                               @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000")
                               @Digits(integer = 7, fraction = 3) BigDecimal quantity) {}

    public record IssueRequest(@NotEmpty @Size(max = 30) List<@Valid @NotNull LineQuantity> lines, Long version) {}

    public record ConsumeRequest(@NotEmpty @Size(max = 30) List<@Valid @NotNull LineQuantity> lines,
                                 @Size(max = 1000) String note, Long version) {}

    public record ReturnRequest(@NotEmpty @Size(max = 30) List<@Valid @NotNull LineQuantity> lines,
                                @NotBlank @Size(min = 5, max = 1000) String reason, Long version) {}

    public record PurchaseOrderRequest(@NotNull UUID supplierId) {}

    public record WorkOrderSummary(UUID id, String ticketNumber, TicketStatus status) {}

    public record LineResponse(UUID id, ItemRef item, BigDecimal qtyRequested, BigDecimal qtyApproved,
                               BigDecimal qtyReserved, BigDecimal qtyIssued, BigDecimal qtyConsumed,
                               BigDecimal qtyReturned) {}

    public record MaterialRequestResponse(UUID id, String requestNumber, MaterialRequestStatus status,
                                          WorkOrderSummary workOrder, WarehouseRef warehouse, CenterRef center,
                                          UserRef requestedBy, UserRef decidedBy, Instant decidedAt,
                                          String decisionNote, String note, String cancelReason,
                                          List<LineResponse> lines, Long version, Instant createdAt) {}

    public record MaterialRequestSummary(UUID id, String requestNumber, MaterialRequestStatus status,
                                         WorkOrderSummary workOrder, UUID warehouseId, CenterRef center,
                                         UserRef requestedBy, Instant createdAt) {}
}
