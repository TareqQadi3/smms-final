package com.smms.controller;

import com.smms.dto.request.CreateWorkOrderRequest;
import com.smms.dto.request.TransitionRequest;
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
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/work-orders")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class WorkOrderController {

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
        @CurrentUser User actor) {
        return ResponseEntity.ok(workOrderService.search(
            centerId, controlRoomId, status, priority, assignedToId, page, size, actor));
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

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('control_room','maintenance_manager','system_admin')")
    public ResponseEntity<WorkOrderResponse> assign(@PathVariable UUID id,
                                                    @RequestParam UUID technicianId,
                                                    @RequestParam(required = false) String note,
                                                    @CurrentUser User actor) {
        return ResponseEntity.ok(workOrderService.assign(id, technicianId, note, actor));
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
    public ResponseEntity<Void> lock(@PathVariable UUID id, @CurrentUser User actor) {
        workOrderService.lock(id, actor);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unlock")
    public ResponseEntity<Void> unlock(@PathVariable UUID id, @CurrentUser User actor) {
        workOrderService.unlock(id, actor);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<TicketHistoryResponse>> history(@PathVariable UUID id,
                                                               @CurrentUser User actor) {
        return ResponseEntity.ok(workOrderService.getHistory(id, actor));
    }

    @PostMapping("/{id}/attachments")
    public ResponseEntity<Void> upload(@PathVariable UUID id,
                                       @RequestParam("file") List<MultipartFile> files,
                                       @CurrentUser User actor) {
        attachmentService.upload(id, files, actor);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/{id}/attachments")
    public ResponseEntity<List<?>> attachments(@PathVariable UUID id,
                                               @CurrentUser User actor) {
        return ResponseEntity.ok(attachmentService.list(id, actor));
    }
}
