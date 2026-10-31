package com.smms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/** Minimal technician entry for the assignment picker: no email, phone or username. */
@Data @Builder
public class AssignableTechnicianResponse {
    UUID id;
    String fullName;
    String fullNameAr;
}
