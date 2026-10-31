package com.smms.entity;

import com.smms.converter.TicketStatusType;
import com.smms.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity @Table(name = "work_orders")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkOrder extends BaseEntity {

    @Column(name = "ticket_number", nullable = false, unique = true, length = 30)
    private String ticketNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "center_id", nullable = false)
    private Center center;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "control_room_id", nullable = false)
    private ControlRoom controlRoom;

    @Type(TicketStatusType.class)
    @Column(nullable = false, columnDefinition = "ticket_status")
    @Builder.Default
    private TicketStatus status = TicketStatus.NEW;

    @Column(name = "current_stage", nullable = false, length = 50)
    @Builder.Default
    private String currentStage = "new";

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "ticket_priority")
    @Builder.Default
    private TicketPriority priority = TicketPriority.medium;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "sub_type")
    private SubType subType;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "location_detail", length = 300)
    private String locationDetail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_id", nullable = false)
    private User requestedBy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "created_by_role", nullable = false, columnDefinition = "user_role")
    private UserRole createdByRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_id")
    private User assignedTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transferred_to_id")
    private User transferredTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_id")
    private User approvedBy;

    @Column(name = "needs_materials")
    @Builder.Default
    private boolean needsMaterials = false;

    @Column(name = "warehouse_notes", columnDefinition = "TEXT")
    private String warehouseNotes;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "approval_decision", columnDefinition = "approval_decision")
    private ApprovalDecision approvalDecision;


    @Column(name = "material_required_at")
    private Instant materialRequiredAt;

    @Column(name = "tech_report", columnDefinition = "TEXT")
    private String techReport;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "sla_deadline")
    private Instant slaDeadline;

    @Column(name = "sla_breached")
    @Builder.Default
    private boolean slaBreached = false;

    @Column(name = "scheduled_date")
    private Instant scheduledDate;

    @Column(name = "completed_date")
    private Instant completedDate;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "reopened_count")
    @Builder.Default
    private int reopenedCount = 0;

    @Column(name = "is_locked")
    @Builder.Default
    private boolean locked = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "locked_by_id")
    private User lockedBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    // ── Feature program 1 columns (V13) ───────────────────────

    /**
     * Optimistic lock. Wrapper type on purpose: a new (unsaved) row has a null version, which keeps
     * Spring Data's isNew() decision unchanged; the column itself is NOT NULL DEFAULT 0.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id")
    private Asset asset;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    /** Non-null while the SLA clock is paused (request for information). */
    @Column(name = "sla_paused_at")
    private Instant slaPausedAt;

    @Column(name = "sla_paused_seconds", nullable = false)
    @Builder.Default
    private long slaPausedSeconds = 0L;

    @Column(name = "is_emergency", nullable = false)
    @Builder.Default
    private boolean emergency = false;

    @Column(name = "emergency_declared_at")
    private Instant emergencyDeclaredAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "emergency_declared_by_id")
    private User emergencyDeclaredBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "requester_confirmation", length = 10)
    private RequesterConfirmation requesterConfirmation;

    @Column(name = "requester_confirmed_at")
    private Instant requesterConfirmedAt;

    public boolean isSlaPaused() { return slaPausedAt != null; }
}
