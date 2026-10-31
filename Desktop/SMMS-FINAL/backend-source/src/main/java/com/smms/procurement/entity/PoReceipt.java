package com.smms.procurement.entity;

import com.smms.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity @Table(name = "po_receipts")
@Getter @Setter @NoArgsConstructor
public class PoReceipt extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "po_id", nullable = false, updatable = false)
    private PurchaseOrder po;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "received_by_id", nullable = false, updatable = false)
    private User receivedBy;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "idempotency_record_id")
    private UUID idempotencyRecordId;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL)
    private List<PoReceiptLine> lines = new ArrayList<>();
}
