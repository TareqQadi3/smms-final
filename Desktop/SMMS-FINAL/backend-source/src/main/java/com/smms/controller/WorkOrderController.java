package com.smms.controller;

import com.smms.dto.request.AssignRequest;
import com.smms.dto.request.CreateWorkOrderRequest;
import com.smms.dto.request.RequestInfoRequest;
import com.smms.dto.request.TransitionRequest;
import com.smms.dto.response.AssignableTechnicianResponse;
import com.smms.dto.response.AttachmentResponse;
import com.smms.dto.response.DuplicateCandidateResponse;
import com.smms.dto.response.TicketHistoryResponse;
import com.smms.dto.response.WorkOrderResponse;
import com.smms.entity.User;
import com.smms.enums.ApprovalDecision;
import com.smms.enums.TicketPriority;
import com.smms.enums.TicketStatus;
import com.smms.service.AttachmentService;
import com.smms.service.WorkOrderService;
import com.smms.util.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.smms.util.PageRequests;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/work-orders")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class WorkOrderController {

    /** API sort name to entity property. priority/status sort by enum declaration order. */
    private static final Map<String, String> SORT_FIELDS = Map.of(
        "createdAt", "createdAt", "updatedAt", "updatedAt", "priority", "priority",
        "status", "status", "ticketNumber", "ticketNumber", "slaDeadline", "slaDeadline");

    private final WorkOrderService workOrderService;
    private final AttachmentService attachmentService;

    @PostMapping
    @PreAuthorize("hasAnyRole('control_room','resident','department','other','technician','system_admin','maintenance_manager','hospital_supervisor')")
    public ResponseEntity<WorkOrderResponse> create(@Valid @RequestBody CreateWorkOrderRequest req,
                                                    @CurrentUser User actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(workOrderService.create(req, actor));
    }

    @GetMapping
    public ResponseEntity<Page<WorkOrderResponse>> search(
        @RequestParam(required = false) UUID centerId,
        @RequestParam(required = false) UUID controlRoomId,
        @RequestParam(required = false) TicketStatus status,
        @RequestParam(required = false) TicketPriority priority,
        @RequestParam(required = false) UUID assignedToId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) List<String> sort,
        @CurrentUser User actor) {
        Pageable pageable = PageRequests.of(page, size, sort, SORT_FIELDS,
            Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(workOrderService.search(
            centerId, controlRoomId, status, priority, assignedToId, pageable, actor));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkOrderResponse> getById(@PathVariable UUID id,
                                                     @CurrentUser User actor) {
        return ResponseEntity.ok(workOrderService.getById(id, actor));
    }

    @PostMapping("/{id}/transition")
    public ResponseEntity<WorkOrderResponse> transition(@PathVariable UUID id,
                                                        @Valid @RequestBody TransitionRequest req,
                                                        @CurrentUser User actor) {
        return ResponseEntity.ok(workOrderService.transition(id, req, actor));
    }

    /**
     * Assigns a technician. technicianId and note are accepted as query parameters (web client) or
     * as a JSON body {technicianId, note} (mobile/API clients); query parameters win when both are
     * present. Missing technicianId is a 400 with an explicit message.
     */
    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('control_room','maintenance_manager','system_admin')")
    public ResponseEntity<WorkOrderResponse> assign(@PathVariable UUID id,
                                                    @RequestParam(required = false) UUID technicianId,
                                                    @RequestParam(required = false) String note,
                                                    @RequestBody(required = false) AssignRequest body,
                                                    @CurrentUser User actor) {
        UUID tech = technicianId != null ? technicianId : (body != null ? body.getTechnicianId() : null);
        String effectiveNote = note != null ? note : (body != null ? body.getNote() : null);
        if (tech == null) {
            throw new IllegalArgumentException("technicianId is required (query parameter or JSON body)");
        }
        return ResponseEntity.ok(workOrderService.assign(id, tech, effectiveNote, actor));
    }

    /** Return for information: asks the requester for more detail (stage info_requested). */
    @PostMapping("/{id}/request-info")
    @PreAuthorize("hasAnyRole('control_room','maintenance_manager')")
    public ResponseEntity<WorkOrderResponse> requestInfo(@PathVariable UUID id,
                                                         @Valid @RequestBody RequestInfoRequest req,
                                                         @CurrentUser User actor) {
        return ResponseEntity.ok(workOrderService.requestInfo(id, req.getNote(), actor));
    }

    /** The requester answers an information request; the ticket returns to plain under_review. */
    @PostMapping("/{id}/provide-info")
    public ResponseEntity<WorkOrderResponse> provideInfo(@PathVariable UUID id,
                                                         @Valid @RequestBody RequestInfoRequest req,
                                                         @CurrentUser User actor) {
        return ResponseEntity.ok(workOrderService.provideInfo(id, req.getNote(), actor));
    }

    /** Non-blocking duplicate hints, limited to tickets the caller may see. */
    @GetMapping("/{id}/possible-duplicates")
    @PreAuthorize("hasAnyRole('control_room','maintenance_manager','hospital_supervisor','electrical_manager','region_supervisor','system_admin')")
    public ResponseEntity<List<DuplicateCandidateResponse>> possibleDuplicates(@PathVariable UUID id,
                                                                               @CurrentUser User actor) {
        return ResponseEntity.ok(workOrderService.possibleDuplicates(id, actor));
    }

    /** Technician picker for the assign dialog; same roles as assign, scoped to the ticket's center. */
    @GetMapping("/{id}/assignable-technicians")
    @PreAuthorize("hasAnyRole('control_room','maintenance_manager','system_admin')")
    public ResponseEntity<List<AssignableTechnicianResponse>> assignableTechnicians(
            @PathVariable UUID id, @CurrentUser User actor) {
        return ResponseEntity.ok(workOrderService.assignableTechnicians(id, actor));
    }

    @PostMapping("/{id}/warehouse")
    @PreAuthorize("hasRole('warehouse')")
    public ResponseEntity<WorkOrderResponse> warehouseAction(
        @PathVariable UUID id,
        @RequestParam boolean materialsAvailable,
        @RequestParam(required = false) String notes,
        @CurrentUser User actor) {
        return ResponseEntity.ok(workOrderService.warehouseAction(id, materialsAvailable, notes, actor));
    }

    @PostMapping("/{id}/approval")
    @PreAuthorize("hasRole('operating_company_manager')")
    public ResponseEntity<WorkOrderResponse> approvalDecision(
        @PathVariable UUID id,
        @RequestParam ApprovalDecision decision,
        @RequestParam(required = false) String note,
        @CurrentUser User actor) {
        return ResponseEntity.ok(workOrderService.approvalDecision(id, decision, note, actor));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('control_room','maintenance_manager','system_admin')")
    public ResponseEntity<WorkOrderResponse> close(@PathVariable UUID id,
                                                   @RequestParam(required = false) String note,
                                                   @CurrentUser User actor) {
        return ResponseEntity.ok(workOrderService.close(id, note, actor));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAnyRole('control_room','system_admin')")
    public ResponseEntity<WorkOrderResponse> reopen(@PathVariable UUID id,
                                                    @RequestParam String note,
                                                    @CurrentUser User actor) {
        return ResponseEntity.ok(workOrderService.reopen(id, note, actor));
    }

    @PostMapping("/{id}/lock")
    @PreAuthorize("hasAnyRole('control_room','technician','hospital_supervisor','region_supervisor','system_admin','maintenance_manager')")
    public ResponseEntity<Void> lock(@PathVariable UUID id, @CurrentUser User actor) {
        workOrderService.lock(id, actor);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAnyRole('control_room','technician','hospital_supervisor','region_supervisor','system_admin','maintenance_manager')")
    public ResponseEntity<Void> unlock(@PathVariable UUID id, @CurrentUser User actor) {
        workOrderService.unlock(id, actor);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<TicketHistoryResponse>> history(@PathVariable UUID id,
                                                               @CurrentUser User actor) {
        return ResponseEntity.ok(workOrderService.getHistory(id, actor));
    }

    /**
     * Optional Idempotency-Key header: a retry with the same key and files returns 200 with
     * Idempotent-Replayed: true and creates nothing; the same key with other files is 422.
     */
    @PostMapping("/{id}/attachments")
    public ResponseEntity<?> upload(@PathVariable UUID id,
                                    @RequestParam("file") List<MultipartFile> files,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                    @CurrentUser User actor) {
        return switch (attachmentService.upload(id, files, actor, idempotencyKey)) {
            case CREATED -> ResponseEntity.status(HttpStatus.CREATED).build();
            case REPLAYED -> ResponseEntity.ok().header("Idempotent-Replayed", "true").build();
            case KEY_REUSED -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                new com.smms.exception.GlobalExceptionHandler.ErrorBody(422,
                    "Idempotency-Key was already used with a different request", java.time.Instant.now()));
        };
    }

    /** Always a download (attachment), with a server-derived content type and no sniffing or caching. */
    @GetMapping("/{id}/attachments/{attId}/content")
    public ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable UUID id,
                                                                         @PathVariable UUID attId,
                                                                         @CurrentUser User actor) {
        AttachmentService.Download d = attachmentService.download(id, attId, actor);
        String safeName = d.fileName().replaceAll("[\\p{Cntrl}\\\\/\"]", "_");
        return ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.parseMediaType(d.contentType()))
            .contentLength(d.size())
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                org.springframework.http.ContentDisposition.attachment()
                    .filename(safeName, java.nio.charset.StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, no-store")
            .header("Content-Security-Policy", "default-src 'none'; sandbox")
            .header("Cross-Origin-Resource-Policy", "same-origin")
            .body(new org.springframework.core.io.FileSystemResource(d.path()));
    }

    @GetMapping("/{id}/attachments")
    public ResponseEntity<List<AttachmentResponse>> attachments(@PathVariable UUID id,
                                               @CurrentUser User actor) {
        return ResponseEntity.ok(attachmentService.list(id, actor));
    }
}
