package com.smms.materials.entity;

import com.smms.entity.Center;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.exception.WorkflowException;
import com.smms.procurement.entity.TenantOwnedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity @Table(name = "material_requests")
@Getter @Setter @NoArgsConstructor
public class MaterialRequest extends TenantOwnedEntity {

    /** Filled by the V38 trigger; read back with refresh after the first flush. */
    @Column(name = "request_number", insertable = false, updatable = false)
    private String requestNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "center_id", nullable = false, updatable = false)
    private Center center;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_order_id", nullable = false, updatable = false)
    private WorkOrder workOrder;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_id", nullable = false, updatable = false)
    private User requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MaterialRequestStatus status = MaterialRequestStatus.submitted;

    @Column(columnDefinition = "TEXT")
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_id")
    private User decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", columnDefinition = "TEXT")
    private String decisionNote;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @Version
    @Column(nullable = false)
    private Long version;

    @OneToMany(mappedBy = "mr", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MaterialRequestLine> lines = new ArrayList<>();

    public void transitionTo(MaterialRequestStatus target) {
        if (!status.canMoveTo(target)) {
            throw new WorkflowException("Material request cannot move from " + status + " to " + target);
        }
        status = target;
    }

    /** Every line fully accounted for (consumed + returned == issued) and nothing left to issue. */
    public boolean readyToClose() {
        return status == MaterialRequestStatus.issued && lines.stream()
            .allMatch(l -> l.outstanding().signum() == 0 && l.fullyIssued());
    }
}
