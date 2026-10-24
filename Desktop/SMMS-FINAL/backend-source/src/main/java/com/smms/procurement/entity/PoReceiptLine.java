package com.smms.procurement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity @Table(name = "po_receipt_lines")
@Getter @Setter @NoArgsConstructor
public class PoReceiptLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false, updatable = false)
    private PoReceipt receipt;

    @Column(name = "po_line_id", nullable = false, updatable = false)
    private UUID poLineId;

    @Column(nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal quantity;

    @Column(name = "movement_id")
    private UUID movementId;
}
