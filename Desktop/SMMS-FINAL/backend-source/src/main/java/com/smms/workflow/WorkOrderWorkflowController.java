package com.smms.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.dto.response.TicketHistoryResponse;
import com.smms.dto.response.WorkOrderResponse;
import com.smms.entity.User;
import com.smms.idempotency.IdempotencyService;
import com.smms.service.WorkOrderService;
import com.smms.util.CurrentUser;
import com.smms.workflow.WorkflowDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Workflow endpoints of the feature program: info requests, escalation, requester confirmation,
 * rating, status link, emergency and work notes. Object-level authorization (404 outside the
 * caller's scope, requester checks) lives in the services via WorkOrderAccessPolicy.
 */
@RestController
@RequestMapping("/api/work-orders")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class WorkOrderWorkflowController {

    private static final String READ_ESCALATIONS =
        "hasAnyRole('system_admin','region_supervisor','hospital_supervisor','control_room',"
        + "'maintenance_manager','electrical_manager','operating_company_manager','warehouse')";

    private final WorkOrderService workOrderService;
    private final EscalationService escalationService;
    private final EmergencyService emergencyService;
    private final RequesterConfirmationService confirmationService;
    private final RatingService ratingService;
    private final StatusLinkService statusLinkService;
    private final WorkNoteService noteService;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    @GetMapping("/{id}/info-requests")
    public List<InfoRequestResponse> infoRequests(@PathVariable UUID id, @CurrentUser User actor) {
        return workOrderService.infoRequests(id, actor);
    }

    @GetMapping("/{id}/escalations")
    @PreAuthorize(READ_ESCALATIONS)
    public List<EscalationResponse> escalations(@PathVariable UUID id, @CurrentUser User actor) {
        return escalationService.list(id, actor);
    }

    @PostMapping("/{id}/escalate")
    @PreAuthorize("hasAnyRole('control_room','maintenance_manager','hospital_supervisor','electrical_manager','system_admin')")
    public EscalationResponse escalate(@PathVariable UUID id, @Valid @RequestBody EscalateRequest req,
                                       @CurrentUser User actor) {
        return escalationService.escalate(id, req.reason(), actor);
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<WorkOrderResponse> confirm(@PathVariable UUID id, @Valid @RequestBody ConfirmRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @CurrentUser User actor) {
        if (key == null || key.isBlank()) {
            return ResponseEntity.ok(confirmationService.confirm(id, req.accepted(), req.note(), actor));
        }
        var out = idempotency.execute(actor, "wo.confirm", key,
            IdempotencyService.fingerprint(objectMapper, req, id),
            recId -> new IdempotencyService.Result<>(
                confirmationService.confirm(id, req.accepted(), req.note(), actor), id),
            ref -> workOrderService.getById(ref, actor));
        return replayAware(out, HttpStatus.OK);
    }

    @PostMapping("/{id}/rating")
    public ResponseEntity<RatingResponse> rate(@PathVariable UUID id, @Valid @RequestBody RatingRequest req,
                                               @CurrentUser User actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ratingService.rate(id, req.score(), req.comment(), actor));
    }

    @GetMapping("/{id}/rating")
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor','hospital_supervisor','control_room',"
        + "'maintenance_manager','electrical_manager','operating_company_manager','warehouse','resident','department')")
    public RatingResponse rating(@PathVariable UUID id, @CurrentUser User actor) {
        return ratingService.get(id, actor);
    }

    @PostMapping("/{id}/status-link")
    public ResponseEntity<StatusLinkResponse> statusLink(@PathVariable UUID id, @CurrentUser User actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .header("Cache-Control", "no-store")
            .body(statusLinkService.create(id, actor));
    }

    @PostMapping("/{id}/emergency")
    @PreAuthorize("hasAnyRole('control_room','maintenance_manager','hospital_supervisor','system_admin')")
    public WorkOrderResponse declareEmergency(@PathVariable UUID id, @Valid @RequestBody ReasonRequest req,
                                              @CurrentUser User actor) {
        return emergencyService.declare(id, req.reason(), actor);
    }

    @PostMapping("/{id}/emergency/clear")
    @PreAuthorize("hasAnyRole('maintenance_manager','hospital_supervisor','system_admin')")
    public WorkOrderResponse clearEmergency(@PathVariable UUID id, @Valid @RequestBody ReasonRequest req,
                                            @CurrentUser User actor) {
        return emergencyService.clear(id, req.reason(), actor);
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize("hasAnyRole('technician','control_room','maintenance_manager','hospital_supervisor','system_admin')")
    public ResponseEntity<TicketHistoryResponse> note(@PathVariable UUID id, @Valid @RequestBody NoteRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @CurrentUser User actor) {
        if (key == null || key.isBlank()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(noteService.add(id, req.text(), actor));
        }
        var out = idempotency.execute(actor, "wo.note", key,
            IdempotencyService.fingerprint(objectMapper, req, id),
            recId -> {
                TicketHistoryResponse h = noteService.add(id, req.text(), actor);
                return new IdempotencyService.Result<>(h, h.getId());
            },
            ref -> noteService.load(ref, id, actor));
        return replayAware(out, HttpStatus.CREATED);
    }

    private static <T> ResponseEntity<T> replayAware(IdempotencyService.Outcome<T> out, HttpStatus fresh) {
        if (out.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(out.body());
        }
        return ResponseEntity.status(fresh).body(out.body());
    }
}
