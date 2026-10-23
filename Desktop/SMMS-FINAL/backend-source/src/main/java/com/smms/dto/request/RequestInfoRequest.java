package com.smms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body for POST /work-orders/{id}/request-info and /provide-info. */
@Data
public class RequestInfoRequest {
    @NotBlank @Size(max = 1000) String note;
}
