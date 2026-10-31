package com.smms.workflow;

import com.smms.entity.User;
import com.smms.enums.SubType;
import com.smms.enums.TicketPriority;

import java.util.UUID;

/**
 * Input of {@code WorkOrderService.createGenerated}: a work order created by the system on behalf of
 * a requester (preventive maintenance). Everything must belong to {@code tenantId}.
 */
public record GeneratedWorkOrderSpec(
    UUID tenantId,
    UUID controlRoomId,
    SubType subType,
    TicketPriority priority,
    String title,
    String description,
    String locationDetail,
    UUID assetId,
    User requester,
    String historyNote) {}
