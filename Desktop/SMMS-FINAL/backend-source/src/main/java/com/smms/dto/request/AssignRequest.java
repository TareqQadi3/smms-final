package com.smms.dto.request;

import lombok.Data;

import java.util.UUID;

/**
 * Optional JSON body for POST /work-orders/{id}/assign. The web client keeps using query
 * parameters; mobile and API clients may send this body instead. Query parameters win when both
 * are present. technicianId is validated in the service layer (400 when neither source has it).
 */
@Data
public class AssignRequest {
    UUID technicianId;
    String note;
}
