package com.smms.controller;

import com.smms.dto.response.WorkOrderResponse;
import com.smms.entity.User;
import com.smms.enums.TicketStatus;
import com.smms.enums.UserRole;
import com.smms.exception.GlobalExceptionHandler;
import com.smms.exception.WorkflowException;
import com.smms.service.AttachmentService;
import com.smms.service.WorkOrderService;
import com.smms.util.CurrentUserArgumentResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3 reproduction of the technician-assignment HTTP 400 (hypotheses H1 to H3) as a standalone
 * MockMvc test: real controller, real GlobalExceptionHandler, mocked service.
 * The stack-level reproduction is recorded in the M3 notes.
 */
class WorkOrderControllerAssignTest {

    private WorkOrderService service;
    private MockMvc mvc;
    private final UUID id = UUID.randomUUID();
    private final UUID tech = UUID.randomUUID();
    private User actor;

    @BeforeEach
    void setUp() {
        service = mock(WorkOrderService.class);
        mvc = MockMvcBuilders.standaloneSetup(new WorkOrderController(service, mock(AttachmentService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
            .build();
        actor = new User();
        actor.setId(UUID.randomUUID());
        actor.setRole(UserRole.control_room);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actor, null, java.util.List.of()));
        when(service.assign(any(), any(), any(), any())).thenReturn(WorkOrderResponse.builder().build());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void queryParametersStillWork() throws Exception {
        mvc.perform(post("/api/work-orders/{id}/assign", id).param("technicianId", tech.toString()).param("note", "n"))
            .andExpect(status().isOk());
        verify(service).assign(id, tech, "n", actor);
    }

    @Test
    void jsonBodyIsAccepted() throws Exception {
        mvc.perform(post("/api/work-orders/{id}/assign", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"technicianId\":\"" + tech + "\",\"note\":\"from body\"}"))
            .andExpect(status().isOk());
        verify(service).assign(id, tech, "from body", actor);
    }

    @Test
    void queryWinsWhenBothArePresent() throws Exception {
        UUID other = UUID.randomUUID();
        mvc.perform(post("/api/work-orders/{id}/assign", id).param("technicianId", tech.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"technicianId\":\"" + other + "\",\"note\":\"b\"}"))
            .andExpect(status().isOk());
        verify(service).assign(id, tech, "b", actor);
    }

    @Test
    void missingTechnicianIsA400WithExplicitMessage() throws Exception {
        mvc.perform(post("/api/work-orders/{id}/assign", id))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("technicianId is required (query parameter or JSON body)"));
        mvc.perform(post("/api/work-orders/{id}/assign", id).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        verify(service, never()).assign(any(), any(), any(), any());
    }

    @Test
    void nonUuidTechnicianIsStill400() throws Exception {
        mvc.perform(post("/api/work-orders/{id}/assign", id).param("technicianId", "undefined"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/work-orders/{id}/assign", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"technicianId\":\"tech.hospital\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void wrongEndpointTransitionIs422NotBadRequest() throws Exception {
        when(service.transition(any(), any(), any())).thenThrow(
            new WorkflowException("Use POST /api/work-orders/{id}/assign to assign a technician"));
        mvc.perform(post("/api/work-orders/{id}/transition", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStatus\":\"" + TicketStatus.ASSIGNED.getDbValue() + "\"}"))
            .andExpect(status().isUnprocessableEntity());
    }
}
