package com.smms.dto.request;

import com.smms.enums.SubType;
import com.smms.enums.TicketPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateWorkOrderRequest {
    @NotNull  UUID controlRoomId;
    @NotNull  SubType subType;
    @NotBlank @Size(max = 500) String title;
    String description;
    String locationDetail;
    @NotNull TicketPriority priority;
    /** Optional asset of the same center and tenant; the department is derived from the requester, never sent. */
    UUID assetId;
}
