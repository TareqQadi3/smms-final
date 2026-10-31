package com.smms.department;

import com.smms.dto.request.CreateDepartmentRequest;
import com.smms.dto.request.UpdateDepartmentRequest;
import com.smms.dto.response.CenterSummary;
import com.smms.dto.response.DepartmentResponse;
import com.smms.dto.response.UserResponse;
import com.smms.entity.Center;
import com.smms.entity.Department;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.mapper.UserMapper;
import com.smms.repository.UserRepository;
import com.smms.security.AdminScopePolicy;
import com.smms.security.TenantSecurityService;
import com.smms.util.InputSanitizer;
import com.smms.util.TenantContext;
import com.smms.workflow.WorkflowDtos.UserRef;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Department CRUD and the user-department link. Everything is tenant scoped (TenantContext, plus the
 * tenant of the parent center); out-of-scope ids are 404 so ids are no oracle.
 * Read: system_admin (tenant), region_supervisor (own region), hospital_supervisor, maintenance_manager,
 * electrical_manager, operating_company_manager, control_room (own center).
 * Write (create, update, deactivate, link users): system_admin, region_supervisor (own region),
 * hospital_supervisor (own center).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DepartmentService {

    static final Set<UserRole> READ_ROLES = Set.of(UserRole.system_admin, UserRole.region_supervisor,
        UserRole.hospital_supervisor, UserRole.maintenance_manager, UserRole.electrical_manager,
        UserRole.operating_company_manager, UserRole.control_room);
    static final Set<UserRole> WRITE_ROLES = Set.of(UserRole.system_admin, UserRole.region_supervisor,
        UserRole.hospital_supervisor);

    /** A member of a department (no contact data). */
    public record MemberResponse(UUID id, String fullName, String fullNameAr, UserRole role) {}

    private final DepartmentCrudRepository repo;
    private final TenantSecurityService tenantSecurity;
    private final AdminScopePolicy policy;
    private final UserRepository userRepo;
    private final UserMapper userMapper;

    // ── Read ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<DepartmentResponse> list(UUID centerId, Boolean active, String q, Pageable pageable, User actor) {
        requireRole(actor, READ_ROLES, "list departments");
        Specification<Department> spec = visibleTo(actor).and((root, query, cb) -> {
            List<Predicate> and = new ArrayList<>();
            if (centerId != null) and.add(cb.equal(root.get("center").get("id"), centerId));
            if (active != null) and.add(cb.equal(root.get("active"), active));
            if (q != null && !q.isBlank()) {
                String like = "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
                and.add(cb.or(cb.like(cb.lower(root.get("name")), like, '\\'),
                    cb.like(cb.lower(root.get("nameAr")), like, '\\'),
                    cb.like(cb.lower(root.get("code")), like, '\\')));
            }
            return cb.and(and.toArray(new Predicate[0]));
        });
        return repo.findAll(spec, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public DepartmentResponse get(UUID id, User actor) {
        requireRole(actor, READ_ROLES, "read departments");
        return toResponse(visible(id, actor));
    }

    @Transactional(readOnly = true)
    public Page<MemberResponse> members(UUID id, Pageable pageable, User actor) {
        requireRole(actor, READ_ROLES, "read department members");
        Department d = visible(id, actor);
        Specification<User> spec = (root, query, cb) -> cb.and(
            cb.equal(root.get("department").get("id"), d.getId()),
            cb.equal(root.get("tenantId"), d.getTenantId()),
            cb.isNull(root.get("deletedAt")));
        return userRepo.findAll(spec, pageable)
            .map(u -> new MemberResponse(u.getId(), u.getFullName(), u.getFullNameAr(), u.getRole()));
    }

    // ── Write ─────────────────────────────────────────────

    public DepartmentResponse create(CreateDepartmentRequest req, User actor) {
        requireRole(actor, WRITE_ROLES, "create departments");
        Center center = tenantSecurity.requireCenter(req.getCenterId());
        policy.assertCenterInScope(actor, center);
        UUID tenantId = policy.resolveTenantForNewEntity(null, center);
        String code = normalizeCode(req.getCode());
        if (repo.existsByCenterIdAndCodeIgnoreCase(center.getId(), code)) {
            throw new ConflictException("Department code already exists in this center: " + code);
        }
        Department d = Department.builder().center(center)
            .name(InputSanitizer.clean(req.getName(), 200)).nameAr(InputSanitizer.clean(req.getNameAr(), 200))
            .code(code).active(true).build();
        d.setTenantId(tenantId);
        Department saved = save(d);
        log.info("AUDIT department created: id={} center={} actor={}", saved.getId(), center.getId(), actor.getId());
        return toResponse(saved);
    }

    public DepartmentResponse update(UUID id, UpdateDepartmentRequest req, User actor) {
        requireRole(actor, WRITE_ROLES, "update departments");
        Department d = writable(id, actor);
        if (req.getCenterId() == null || !req.getCenterId().equals(d.getCenter().getId())) {
            throw new WorkflowException("A department cannot be moved to another center");
        }
        String code = normalizeCode(req.getCode());
        if (!code.equalsIgnoreCase(d.getCode())
                && repo.existsByCenterIdAndCodeIgnoreCaseAndIdNot(d.getCenter().getId(), code, d.getId())) {
            throw new ConflictException("Department code already exists in this center: " + code);
        }
        d.setCode(code);
        d.setName(InputSanitizer.clean(req.getName(), 200));
        d.setNameAr(InputSanitizer.clean(req.getNameAr(), 200));
        d.setActive(req.isActive());
        Department saved = save(d);
        log.info("AUDIT department updated: id={} active={} actor={}", saved.getId(), saved.isActive(), actor.getId());
        return toResponse(saved);
    }

    public DepartmentResponse deactivate(UUID id, User actor) {
        requireRole(actor, WRITE_ROLES, "deactivate departments");
        Department d = writable(id, actor);
        if (!d.isActive()) throw new WorkflowException("The department is already inactive");
        d.setActive(false);
        Department saved = save(d);
        log.info("AUDIT department deactivated: id={} actor={}", saved.getId(), actor.getId());
        return toResponse(saved);
    }

    /**
     * Links (or unlinks, with a null department) a user. The user must be manageable by the actor
     * (AdminScopePolicy: scope 404, rank 403); the department must belong to the user's tenant AND
     * center and be active (else 404 / 422). Nothing is guessed from names.
     */
    public UserResponse linkUser(UUID userId, UUID departmentId, User actor) {
        requireRole(actor, WRITE_ROLES, "link users to departments");
        User user = tenantSecurity.requireUser(userId);
        policy.assertCanManageUser(actor, user);
        if (user.getTenantId() == null) throw new WorkflowException("The user has no tenant");
        UUID before = user.getDepartment() != null ? user.getDepartment().getId() : null;
        if (departmentId == null) {
            user.setDepartment(null);
        } else {
            Department d = repo.findAll((root, q, cb) -> cb.and(
                    cb.equal(root.get("id"), departmentId),
                    cb.equal(root.get("tenantId"), user.getTenantId()),
                    cb.isNull(root.get("deletedAt"))))
                .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Department not found"));
            if (user.getCenter() == null || d.getCenter() == null
                    || !user.getCenter().getId().equals(d.getCenter().getId())) {
                throw new WorkflowException("The department must be in the user's center");
            }
            if (!d.isActive()) throw new WorkflowException("The department is inactive");
            user.setDepartment(d);
        }
        User saved = userRepo.save(user);
        log.info("AUDIT user department link: user={} from={} to={} actor={}", userId, before, departmentId, actor.getId());
        return userMapper.toResponse(saved);
    }

    // ── helpers ───────────────────────────────────────────

    private Department save(Department d) {
        try {
            return repo.saveAndFlush(d);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Department code already exists in this center");
        }
    }

    private Department visible(UUID id, User actor) {
        Specification<Department> byId = (root, q, cb) -> cb.equal(root.get("id"), id);
        return repo.findOne(byId.and(visibleTo(actor)))
            .orElseThrow(() -> new ResourceNotFoundException("Department not found"));
    }

    /** Visible and writable: the center must be in the actor's write scope (region / own center). */
    private Department writable(UUID id, User actor) {
        Department d = visible(id, actor);
        policy.assertCenterInScope(actor, d.getCenter());
        return d;
    }

    /** Tenant + soft-delete + role scope. A role without a location gets nothing. */
    private Specification<Department> visibleTo(User actor) {
        UUID tenant = TenantContext.get();
        UUID centerId = actor.getCenter() != null ? actor.getCenter().getId() : null;
        UUID regionId = actor.getRegion() != null ? actor.getRegion().getId() : null;
        UserRole role = actor.getRole();
        return (root, q, cb) -> {
            List<Predicate> and = new ArrayList<>();
            and.add(cb.isNull(root.get("deletedAt")));
            if (tenant != null) and.add(cb.equal(root.get("tenantId"), tenant));
            switch (role) {
                case system_admin -> { }
                case region_supervisor -> and.add(regionId == null ? cb.disjunction()
                    : cb.equal(root.get("center").get("region").get("id"), regionId));
                default -> and.add(centerId == null ? cb.disjunction()
                    : cb.equal(root.get("center").get("id"), centerId));
            }
            return cb.and(and.toArray(new Predicate[0]));
        };
    }

    private static void requireRole(User actor, Set<UserRole> roles, String what) {
        if (actor == null || actor.getRole() == null || !roles.contains(actor.getRole())) {
            throw new AccessDeniedException("Role not permitted to " + what);
        }
    }

    private static String normalizeCode(String code) {
        String c = InputSanitizer.clean(code, 40);
        if (c == null || c.isBlank()) throw new IllegalArgumentException("code is required");
        return c.trim().toUpperCase(Locale.ROOT);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    DepartmentResponse toResponse(Department d) {
        Center c = d.getCenter();
        return DepartmentResponse.builder().id(d.getId()).name(d.getName()).nameAr(d.getNameAr())
            .code(d.getCode()).active(d.isActive())
            .center(c == null ? null : CenterSummary.builder().id(c.getId()).name(c.getName())
                .nameAr(c.getNameAr()).code(c.getCode()).build())
            .build();
    }
}
