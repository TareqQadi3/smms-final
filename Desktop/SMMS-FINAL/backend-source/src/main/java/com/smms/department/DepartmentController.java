package com.smms.department;

import com.smms.dto.request.CreateDepartmentRequest;
import com.smms.dto.request.UpdateDepartmentRequest;
import com.smms.dto.response.DepartmentResponse;
import com.smms.entity.User;
import com.smms.util.CurrentUser;
import com.smms.util.PageRequests;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private static final Map<String, String> SORT_FIELDS =
        Map.of("name", "name", "code", "code", "createdAt", "createdAt");
    private static final String READ = "hasAnyRole('system_admin','region_supervisor','hospital_supervisor',"
        + "'maintenance_manager','electrical_manager','operating_company_manager','control_room')";
    private static final String WRITE = "hasAnyRole('system_admin','region_supervisor','hospital_supervisor')";

    private final DepartmentService service;

    @GetMapping
    @PreAuthorize(READ)
    public Page<DepartmentResponse> list(@RequestParam(required = false) UUID centerId,
                                         @RequestParam(required = false) Boolean active,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) List<String> sort,
                                         @CurrentUser User actor) {
        Pageable pageable = PageRequests.of(page, size, sort, SORT_FIELDS, Sort.by(Sort.Direction.ASC, "name"));
        return service.list(centerId, active, q, pageable, actor);
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<DepartmentResponse> create(@Valid @RequestBody CreateDepartmentRequest req,
                                                     @CurrentUser User actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req, actor));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public DepartmentResponse get(@PathVariable UUID id, @CurrentUser User actor) {
        return service.get(id, actor);
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    public DepartmentResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateDepartmentRequest req,
                                     @CurrentUser User actor) {
        return service.update(id, req, actor);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize(WRITE)
    public DepartmentResponse deactivate(@PathVariable UUID id, @CurrentUser User actor) {
        return service.deactivate(id, actor);
    }

    @GetMapping("/{id}/members")
    @PreAuthorize(READ)
    public Page<DepartmentService.MemberResponse> members(@PathVariable UUID id,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size,
                                                          @RequestParam(required = false) List<String> sort,
                                                          @CurrentUser User actor) {
        Pageable pageable = PageRequests.of(page, size, sort, Map.of("fullName", "fullName"),
            Sort.by(Sort.Direction.ASC, "fullName"));
        return service.members(id, pageable, actor);
    }
}
