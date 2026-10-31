package com.smms.authz;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build-time guard: a new endpoint cannot ship without an authorization decision, and the
 * privileged parts of the API stay role-restricted. It reads the real controllers by reflection,
 * so nothing here is a copy of the code under test.
 *
 * Rules:
 *  1. every handler has @PreAuthorize (own or class level) unless its path is explicitly allowlisted;
 *  2. write handlers (POST/PUT/PATCH/DELETE) under /api/admin, /api/platform, /api/import,
 *     /api/users and /api/maintenance-types name specific roles, never a bare isAuthenticated();
 *  3. everything under /api/platform needs the platform-admin expression;
 *  4. the role sets of the sensitive operations are pinned (privilege escalation guard);
 *  5. the allowlist has no stale entries.
 *
 * /api/work-orders/** is authorised in WorkOrderAccessPolicy (tenant and role scope per object)
 * on top of the class-level isAuthenticated(). /api/notifications/** is scoped to the caller in the
 * service. /api/auth/** and /actuator/health are public by SecurityConfig.
 */
class EndpointAuthorizationCoverageTest {

    /** Paths (prefix match) allowed to have no @PreAuthorize, each with the reason it is safe. */
    private static final Map<String, String> ALLOWLIST = Map.of(
        "/api/auth/", "public by SecurityConfig: login, refresh, logout",
        "/api/users/me", "acts on the caller only (profile, change-password)",
        "/api/notifications", "service reads and writes only the caller's rows",
        "/api/public/", "token-based minimal status, rate limited, feature flag");

    private static final List<String> SENSITIVE_PREFIXES = List.of(
        "/api/admin", "/api/platform", "/api/import", "/api/users", "/api/maintenance-types");

    private static final Pattern ROLE = Pattern.compile("'([a-z_]+)'");
    private static final String SYSTEM_ADMIN = "system_admin";

    private record Endpoint(Class<?> type, Method method, Set<RequestMethod> verbs, String path, String expr) {
        boolean write() {
            return verbs.stream().anyMatch(v -> v != RequestMethod.GET && v != RequestMethod.HEAD && v != RequestMethod.OPTIONS);
        }
        String id() { return verbs + " " + path + " (" + type.getSimpleName() + "." + method.getName() + ")"; }
        /** Roles named in hasRole/hasAnyRole; empty for isAuthenticated() or no annotation. */
        Set<String> roles() {
            Set<String> out = new TreeSet<>();
            if (expr == null || !(expr.contains("hasRole(") || expr.contains("hasAnyRole("))) return out;
            Matcher m = ROLE.matcher(expr.replaceAll("T\\([^)]*\\)\\.[A-Za-z]+\\(\\)", ""));
            while (m.find()) out.add(m.group(1));
            return out;
        }
    }

    private static List<Endpoint> endpoints() throws Exception {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Endpoint> out = new ArrayList<>();
        for (BeanDefinition bd : scan.findCandidateComponents("com.smms")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            // test-only fixtures (e.g. SecurityChainTest.Probe) are not application endpoints
            if (type.getProtectionDomain().getCodeSource().getLocation().getPath().contains("test-classes")) continue;
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] prefixes = base != null && base.path().length > 0 ? base.path() : new String[] {""};
            PreAuthorize onClass = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class);
            for (Method m : type.getDeclaredMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) continue;
                PreAuthorize own = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
                PreAuthorize effective = own != null ? own : onClass;
                String[] subs = rm.path().length > 0 ? rm.path() : new String[] {""};
                Set<RequestMethod> verbs = rm.method().length > 0
                    ? Set.of(rm.method()) : Set.of(RequestMethod.GET, RequestMethod.POST);
                for (String p : prefixes) {
                    for (String s : subs) {
                        out.add(new Endpoint(type, m, verbs, p + s, effective == null ? null : effective.value()));
                    }
                }
            }
        }
        return out;
    }

    private static String allowlistKey(String path) {
        return ALLOWLIST.keySet().stream().filter(path::startsWith).findFirst().orElse(null);
    }

    @Test
    void scanFindsTheControllers() throws Exception {
        List<Endpoint> all = endpoints();
        assertThat(all).hasSizeGreaterThan(40);
        assertThat(all).extracting(e -> e.type().getSimpleName())
            .contains("AdminController", "UserController", "WorkOrderController", "TenantController",
                "TenantAssignmentController", "AuthController");
    }

    @Test
    void everyHandlerHasAnAuthorizationDecision() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            if (e.expr() == null && allowlistKey(e.path()) == null) missing.add(e.id());
        }
        assertThat(missing).as("handlers without @PreAuthorize and not on the allowlist").isEmpty();
    }

    @Test
    void noHandlerIsOpenedUpWithPermitAll() throws Exception {
        for (Endpoint e : endpoints()) {
            assertThat(e.expr()).as(e.id()).satisfiesAnyOf(
                x -> assertThat(x).isNull(), x -> assertThat(x).doesNotContain("permitAll"));
        }
    }

    @Test
    void writeHandlersOfSensitiveAreasNameRoles() throws Exception {
        List<String> weak = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            boolean sensitive = SENSITIVE_PREFIXES.stream().anyMatch(e.path()::startsWith);
            if (!sensitive || !e.write() || allowlistKey(e.path()) != null) continue;
            if (e.roles().isEmpty()) weak.add(e.id() + " -> " + e.expr());
        }
        assertThat(weak).as("write handlers that rely on bare authentication").isEmpty();
    }

    @Test
    void platformEndpointsRequireThePlatformAdminExpression() throws Exception {
        String required = com.smms.controller.TenantController.PLATFORM_ADMIN_ONLY;
        List<String> wrong = new ArrayList<>();
        int seen = 0;
        for (Endpoint e : endpoints()) {
            if (!e.path().startsWith("/api/platform")
                    && !e.path().contains("tenantless-users") && !e.path().matches("/api/admin/users/\\{id}/tenant")
                    && !e.path().matches("/api/users/\\{id}/tenant")) continue;
            seen++;
            if (!required.equals(e.expr())) wrong.add(e.id() + " -> " + e.expr());
        }
        assertThat(seen).isGreaterThan(5);
        assertThat(wrong).as("platform-level handlers not using PLATFORM_ADMIN_ONLY").isEmpty();
    }

    // ── pinned role sets: widening any of them is a deliberate, reviewed change ──

    private static Set<String> rolesOf(String verb, String pathSuffix) throws Exception {
        List<Set<String>> found = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            if (e.verbs().stream().anyMatch(v -> v.name().equals(verb)) && e.path().equals(pathSuffix)) found.add(e.roles());
        }
        assertThat(found).as("%s %s must exist", verb, pathSuffix).isNotEmpty();
        return found.get(0);
    }

    @Test
    void roleSetsOfSensitiveOperationsArePinned() throws Exception {
        Set<String> admins = Set.of("system_admin", "region_supervisor", "hospital_supervisor");
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put("POST /api/admin/regions", Set.of(SYSTEM_ADMIN));
        expected.put("PUT /api/admin/regions/{id}", Set.of(SYSTEM_ADMIN));
        expected.put("POST /api/admin/centers", Set.of("system_admin", "region_supervisor"));
        expected.put("PUT /api/admin/centers/{id}", Set.of("system_admin", "region_supervisor"));
        expected.put("POST /api/admin/control-rooms", admins);
        expected.put("PUT /api/admin/control-rooms/{id}", admins);
        expected.put("POST /api/users", admins);
        expected.put("PUT /api/users/{id}", admins);
        expected.put("PUT /api/users/{id}/active", admins);
        expected.put("POST /api/users/{id}/control-rooms", admins);
        expected.put("POST /api/users/{id}/reset-password", Set.of(SYSTEM_ADMIN));
        expected.put("POST /api/import/residents", admins);
        expected.put("POST /api/import/departments", admins);
        expected.put("POST /api/maintenance-types", Set.of(SYSTEM_ADMIN));
        expected.put("PUT /api/maintenance-types/{id}", Set.of(SYSTEM_ADMIN));
        expected.put("DELETE /api/maintenance-types/{id}", Set.of(SYSTEM_ADMIN));
        expected.put("POST /api/work-orders/{id}/approval", Set.of("operating_company_manager"));
        expected.put("POST /api/work-orders/{id}/warehouse", Set.of("warehouse"));
        expected.put("POST /api/work-orders/{id}/assign", Set.of("control_room", "maintenance_manager", SYSTEM_ADMIN));
        expected.put("POST /api/work-orders/{id}/reopen", Set.of("control_room", SYSTEM_ADMIN));
        expected.put("POST /api/work-orders/{id}/close", Set.of("control_room", "maintenance_manager", SYSTEM_ADMIN));
        for (var entry : expected.entrySet()) {
            String[] parts = entry.getKey().split(" ", 2);
            assertThat(rolesOf(parts[0], parts[1])).as(entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    @Test
    void endUserAndFieldRolesAppearInNoAdministrativeEndpoint() throws Exception {
        Set<String> lowPrivilege = Set.of("technician", "resident", "department", "other", "warehouse",
            "control_room", "maintenance_manager", "electrical_manager", "operating_company_manager");
        List<String> leaks = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            boolean admin = e.path().startsWith("/api/admin") || e.path().startsWith("/api/platform")
                || e.path().startsWith("/api/import") || e.path().startsWith("/api/users");
            if (!admin || !e.write() || allowlistKey(e.path()) != null) continue;
            for (String r : e.roles()) if (lowPrivilege.contains(r)) leaks.add(e.id() + " allows " + r);
        }
        assertThat(leaks).isEmpty();
    }

    @Test
    void allowlistHasNoStaleEntries() throws Exception {
        List<Endpoint> all = endpoints();
        for (String prefix : ALLOWLIST.keySet()) {
            assertThat(all).as("allowlist entry %s matches a handler", prefix)
                .anyMatch(e -> e.path().startsWith(prefix));
        }
    }
}
