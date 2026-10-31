package com.smms.service;

import com.smms.entity.Center;
import com.smms.entity.ControlRoom;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one set of account rules shared by single creation/update ({@link UserService}) and every
 * row of a bulk import ({@link BulkImportService}), so both paths accept and reject the same input
 * with the same message. Pure and stateless: no repository access (uniqueness and scope checks stay
 * with the callers, which know their own lookups).
 *
 * Fields are validated by {@link #validateFields}, role links by {@link #validateLinks}. The first
 * form returns messages (bulk collects them per row); {@link #firstOrNull}/{@link #require} turn
 * the list into the single-user IllegalArgumentException (HTTP 400).
 */
@Component
public class UserAccountValidator {

    public static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9._@-]+$");
    public static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public static final int USERNAME_MIN = 3;
    public static final int USERNAME_MAX = 100;
    public static final int PASSWORD_MIN = 8;
    public static final int PASSWORD_MAX = 128;
    public static final int EMAIL_MAX = 255;
    public static final int PHONE_MAX = 20;
    public static final int NAME_MAX = 200;

    public static final Set<UserRole> REGION_ROLES = Set.of(UserRole.region_supervisor);
    public static final Set<UserRole> CENTER_ROLES = Set.of(
        UserRole.hospital_supervisor, UserRole.control_room, UserRole.technician,
        UserRole.maintenance_manager, UserRole.electrical_manager, UserRole.warehouse,
        UserRole.operating_company_manager, UserRole.resident, UserRole.department);

    /**
     * Account input. A null field is "not supplied": it is only an error where the rule says required.
     * {@code displayName} is the full name, or the department name for a department import.
     */
    public record Fields(String username, String password, String email, String phone,
                         String displayName, UserRole role,
                         String departmentName, String departmentEmail) {}

    /** Required fields, then format and length of every supplied field. Empty list = valid. */
    public List<String> validateFields(Fields f) {
        List<String> errors = new ArrayList<>();
        if (!StringUtils.hasText(f.username())) errors.add("username is required");
        else errors.addAll(validateUsername(f.username()));
        if (!StringUtils.hasText(f.password())) errors.add("password is required");
        else errors.addAll(validatePassword(f.password()));
        boolean unnamedDepartment = f.role() == UserRole.department && !StringUtils.hasText(f.departmentName());
        // An unnamed department is reported once, below, as "departmentName is required"
        if (!StringUtils.hasText(f.displayName()) && !unnamedDepartment) errors.add("fullName is required");
        errors.addAll(validateContact(f.email(), f.phone(), f.displayName()));
        if (StringUtils.hasText(f.departmentEmail()) && !validEmail(f.departmentEmail())) {
            errors.add("departmentEmail is not a valid address");
        }
        if (unnamedDepartment) errors.add("departmentName is required");
        return errors;
    }

    /** Optional contact fields only (used by update, where every field is optional). */
    public List<String> validateContact(String email, String phone, String name) {
        List<String> errors = new ArrayList<>();
        if (StringUtils.hasText(email) && !validEmail(email)) errors.add("email is not a valid address");
        if (StringUtils.hasText(phone) && phone.length() > PHONE_MAX) {
            errors.add("phone must be at most " + PHONE_MAX + " characters");
        }
        if (StringUtils.hasText(name) && name.length() > NAME_MAX) {
            errors.add("name must be at most " + NAME_MAX + " characters");
        }
        return errors;
    }

    public List<String> validateUsername(String username) {
        if (username.length() < USERNAME_MIN || username.length() > USERNAME_MAX
                || !USERNAME.matcher(username).matches()) {
            return List.of("username must be 3-100 characters: letters, digits, dots, hyphens, underscores or @");
        }
        return List.of();
    }

    public List<String> validatePassword(String password) {
        if (password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            return List.of("password must be " + PASSWORD_MIN + "-" + PASSWORD_MAX + " characters");
        }
        return List.of();
    }

    /**
     * Final state of an account: required links per role, and the center must belong to the region
     * when both are set. A department also needs a department name here, so a role change to
     * department cannot leave an unnamed department behind.
     */
    public List<String> validateLinks(UserRole role, Center center, Region region, String departmentName) {
        List<String> errors = new ArrayList<>();
        if (REGION_ROLES.contains(role) && region == null) {
            errors.add("Region is required for role: " + role);
        }
        if (CENTER_ROLES.contains(role) && center == null) {
            errors.add("Center is required for role: " + role);
        }
        if (center != null && region != null
                && (center.getRegion() == null || !region.getId().equals(center.getRegion().getId()))) {
            errors.add("Center does not belong to the given region");
        }
        if (role == UserRole.department && !StringUtils.hasText(departmentName)) {
            errors.add("departmentName is required");
        }
        return errors;
    }

    /**
     * Control-room grant rule. A non-admin actor may only grant rooms of the user's own center
     * (403, as before). A department account is bound to its center for everybody: even a system
     * administrator cannot give it a room of another center (400).
     */
    public void assertGrantAllowed(User actor, UserRole userRole, Center userCenter, ControlRoom cr) {
        boolean sameCenter = userCenter == null
            || (cr.getCenter() != null && userCenter.getId().equals(cr.getCenter().getId()));
        if (sameCenter) return;
        if (userRole == UserRole.department) {
            throw new IllegalArgumentException("A department can only access control rooms of its own center");
        }
        if (actor == null || actor.getRole() == UserRole.system_admin) return;
        throw new AccessDeniedException("Control room belongs to a different center");
    }

    public String firstOrNull(List<String> errors) {
        return errors.isEmpty() ? null : errors.get(0);
    }

    /** Single-user path: the first error becomes a 400. */
    public void require(List<String> errors) {
        if (!errors.isEmpty()) throw new IllegalArgumentException(errors.get(0));
    }

    private static boolean validEmail(String email) {
        return email.length() <= EMAIL_MAX && EMAIL.matcher(email).matches();
    }
}
