package com.smms.department;

import com.smms.dto.response.UserResponse;
import com.smms.entity.User;
import com.smms.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Links a user to a department row ({@code departmentId: null} unlinks). Same actors as user administration. */
@RestController
@RequiredArgsConstructor
public class UserDepartmentController {

    /** Body of PUT /users/{id}/department. */
    public record LinkRequest(UUID departmentId) {}

    private final DepartmentService service;

    @PutMapping({"/api/users/{id}/department", "/api/admin/users/{id}/department"})
    @PreAuthorize("hasAnyRole('system_admin','region_supervisor','hospital_supervisor')")
    public UserResponse link(@PathVariable UUID id, @RequestBody LinkRequest body, @CurrentUser User actor) {
        return service.linkUser(id, body.departmentId(), actor);
    }
}
