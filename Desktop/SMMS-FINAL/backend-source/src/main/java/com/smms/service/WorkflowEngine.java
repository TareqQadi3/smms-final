package com.smms.service;

import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import com.smms.exception.WorkflowException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Enforces the SMMS loop-based workflow transitions.
 * Any invalid transition throws WorkflowException.
 */
@Component
public class WorkflowEngine {

    // Map: fromStatus → allowed (toStatus, roles)
    private static final Map<TicketStatus, Map<TicketStatus, Set<UserRole>>> TRANSITIONS = Map.of(

        TicketStatus.NEW, Map.of(
            TicketStatus.UNDER_REVIEW, Set.of(UserRole.control_room),
            TicketStatus.CANCELLED,    Set.of(UserRole.control_room, UserRole.maintenance_manager, UserRole.hospital_supervisor)
        ),

        TicketStatus.UNDER_REVIEW, Map.of(
            TicketStatus.ASSIGNED,   Set.of(UserRole.control_room),
            TicketStatus.CANCELLED,  Set.of(UserRole.control_room, UserRole.maintenance_manager)
        ),

        TicketStatus.ASSIGNED, Map.of(
            TicketStatus.IN_PROGRESS, Set.of(UserRole.technician),
            TicketStatus.CANCELLED,   Set.of(UserRole.control_room, UserRole.maintenance_manager)
        ),

        TicketStatus.IN_PROGRESS, Map.of(
            TicketStatus.COMPLETED,          Set.of(UserRole.technician),
            TicketStatus.WAITING_MATERIALS,  Set.of(UserRole.technician),
            TicketStatus.CANCELLED,          Set.of(UserRole.control_room, UserRole.maintenance_manager)
        ),

        TicketStatus.WAITING_MATERIALS, Map.of(
            TicketStatus.WAITING_APPROVAL,  Set.of(UserRole.warehouse),
            TicketStatus.IN_PROGRESS,       Set.of(UserRole.warehouse)  // materials provided directly
        ),

        TicketStatus.WAITING_APPROVAL, Map.of(
            TicketStatus.IN_PROGRESS,        Set.of(UserRole.operating_company_manager),  // approved
            TicketStatus.WAITING_MATERIALS,  Set.of(UserRole.operating_company_manager)   // rejected
        ),

        TicketStatus.COMPLETED, Map.of(
            TicketStatus.CLOSED,       Set.of(UserRole.control_room, UserRole.maintenance_manager),
            TicketStatus.IN_PROGRESS,  Set.of(UserRole.control_room)  // reopen
        )
    );

    public void validate(TicketStatus from, TicketStatus to, UserRole actorRole) {
        // system_admin can perform any structurally valid transition
        if (actorRole == UserRole.system_admin) {
            Map<TicketStatus, Set<UserRole>> allowed = TRANSITIONS.get(from);
            if (allowed == null || !allowed.containsKey(to)) {
                throw new WorkflowException(
                    String.format("Transition %s → %s is not allowed", from, to));
            }
            return;
        }
        Map<TicketStatus, Set<UserRole>> allowed = TRANSITIONS.get(from);
        if (allowed == null) {
            throw new WorkflowException("No transitions allowed from status: " + from);
        }
        Set<UserRole> roles = allowed.get(to);
        if (roles == null) {
            throw new WorkflowException(
                String.format("Transition %s → %s is not allowed", from, to));
        }
        if (!roles.contains(actorRole)) {
            throw new WorkflowException(
                String.format("Role [%s] cannot perform transition %s → %s", actorRole, from, to));
        }
    }

    public boolean canTransition(TicketStatus from, TicketStatus to, UserRole actorRole) {
        try { validate(from, to, actorRole); return true; }
        catch (WorkflowException e) { return false; }
    }

    /** Returns next stage label for UI */
    public String resolveStage(TicketStatus status) {
        return switch (status) {
            case NEW             -> "new";
            case UNDER_REVIEW     -> "under_review";
            case ASSIGNED         -> "assigned";
            case IN_PROGRESS      -> "in_progress";
            case WAITING_MATERIALS-> "waiting_materials";
            case WAITING_APPROVAL -> "waiting_approval";
            case COMPLETED        -> "completed";
            case CLOSED           -> "closed";
            case CANCELLED        -> "cancelled";
        };
    }
}
