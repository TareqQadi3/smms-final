package com.smms.config;

import com.smms.entity.MaintenanceType;
import com.smms.entity.Tenant;
import com.smms.entity.TenantSubscription;
import com.smms.entity.User;
import com.smms.enums.ControlRoomType;
import com.smms.enums.PlanType;
import com.smms.enums.TenantStatus;
import com.smms.enums.UserRole;
import com.smms.repository.MaintenanceTypeRepository;
import com.smms.repository.TenantRepository;
import com.smms.repository.TenantSubscriptionRepository;
import com.smms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final DataSource dataSource;
    private final MaintenanceTypeRepository maintenanceTypeRepo;
    private final TenantRepository tenantRepo;
    private final TenantSubscriptionRepository subscriptionRepo;

    @Override
    public void run(String... args) {
        migrateEnums();
        Tenant defaultTenant = ensureDefaultTenant();
        backfillTenantId(defaultTenant.getId());
        ensureDefaultSubscription(defaultTenant);
        createAdminIfAbsent();
        seedMaintenanceTypes(defaultTenant);
    }

    private void migrateEnums() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TYPE user_role ADD VALUE IF NOT EXISTS 'other'");
            log.debug("user_role enum migration OK");
        } catch (Exception e) {
            log.warn("user_role enum migration skipped: {}", e.getMessage());
        }
    }

    /** Creates the default tenant if not present; returns it. */
    private Tenant ensureDefaultTenant() {
        return tenantRepo.findBySlug("default")
            .orElseGet(() -> {
                Tenant t = tenantRepo.save(Tenant.builder()
                    .name("Jazan Health Cluster")
                    .nameAr("تجمع جازان الصحي")
                    .slug("default")
                    .isActive(true)
                    .build());
                log.info("Default tenant created: id={}", t.getId());
                return t;
            });
    }

    /**
     * Fills tenant_id on rows that have none. The tenant is derived from the row's parent
     * (region -> center -> control room / user / work order), never blindly from the default tenant,
     * so rows of a second tenant keep their own tenant. Rows that still have no tenant afterwards
     * fall back to the default tenant only when exactly one tenant exists; otherwise they are
     * logged and left NULL (such accounts cannot sign in until an admin assigns a tenant).
     * Idempotent: only rows with a NULL tenant_id are touched, system_admin always stays NULL.
     */
    private void backfillTenantId(java.util.UUID defaultTenantId) {
        boolean singleTenant = tenantRepo.count() == 1;
        // user role cast: the column is a PostgreSQL enum
        String notAdmin = "role::text != 'system_admin'";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            int total = 0;

            // 1. Regions are the roots: nothing to derive from, so only the single-tenant fallback applies
            if (singleTenant) total += update(stmt, "regions fallback",
                "UPDATE regions SET tenant_id = '" + defaultTenantId + "' WHERE tenant_id IS NULL");

            // 2. Derive downwards from the parent
            total += update(stmt, "centers from regions",
                "UPDATE centers c SET tenant_id = r.tenant_id FROM regions r "
                + "WHERE c.region_id = r.id AND c.tenant_id IS NULL AND r.tenant_id IS NOT NULL");
            total += update(stmt, "control_rooms from centers",
                "UPDATE control_rooms x SET tenant_id = c.tenant_id FROM centers c "
                + "WHERE x.center_id = c.id AND x.tenant_id IS NULL AND c.tenant_id IS NOT NULL");
            total += update(stmt, "users from centers",
                "UPDATE users u SET tenant_id = c.tenant_id FROM centers c "
                + "WHERE u.center_id = c.id AND u.tenant_id IS NULL AND u." + notAdmin
                + " AND c.tenant_id IS NOT NULL");
            total += update(stmt, "users from regions",
                "UPDATE users u SET tenant_id = r.tenant_id FROM regions r "
                + "WHERE u.region_id = r.id AND u.tenant_id IS NULL AND u." + notAdmin
                + " AND r.tenant_id IS NOT NULL");
            total += update(stmt, "work_orders from centers",
                "UPDATE work_orders w SET tenant_id = c.tenant_id FROM centers c "
                + "WHERE w.center_id = c.id AND w.tenant_id IS NULL AND c.tenant_id IS NOT NULL");

            // 3. Leftovers: default tenant only when it is the only tenant, else report and leave NULL
            String[] derived = {"users", "centers", "control_rooms", "work_orders"};
            for (String table : derived) {
                String where = table.equals("users")
                    ? "WHERE tenant_id IS NULL AND " + notAdmin : "WHERE tenant_id IS NULL";
                if (singleTenant) {
                    total += update(stmt, table + " fallback",
                        "UPDATE " + table + " SET tenant_id = '" + defaultTenantId + "' " + where);
                } else {
                    long left = count(stmt, "SELECT count(*) FROM " + table + " " + where);
                    if (left > 0) {
                        log.warn("Tenant backfill: {} rows in '{}' have no tenant and cannot be derived;"
                            + " left NULL, assign a tenant manually", left, table);
                    }
                }
            }

            // 4. Tables not covered by this change keep the previous single default-tenant backfill
            String[] others = {
                "maintenance_types", "ticket_history", "notifications", "attachments",
                "departments", "assets", "inventory_items", "inventory_movements"
            };
            for (String table : others) {
                total += update(stmt, table + " default",
                    "UPDATE " + table + " SET tenant_id = '" + defaultTenantId + "' WHERE tenant_id IS NULL");
            }

            // Ensure existing system_admin users always have null tenantId
            update(stmt, "system_admin clear",
                "UPDATE users SET tenant_id = NULL WHERE role::text = 'system_admin' AND tenant_id IS NOT NULL");
            if (total > 0) log.info("Backfilled tenant_id for {} rows", total);
        } catch (Exception e) {
            log.warn("Tenant backfill failed: {}", e.getMessage());
        }
    }

    private int update(Statement stmt, String label, String sql) {
        try {
            int rows = stmt.executeUpdate(sql);
            if (rows > 0) log.info("Tenant backfill [{}]: {} rows", label, rows);
            return rows;
        } catch (Exception e) {
            log.debug("Backfill skip {}: {}", label, e.getMessage());
            return 0;
        }
    }

    private long count(Statement stmt, String sql) {
        try (java.sql.ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) {
            log.debug("Count skip: {}", e.getMessage());
            return 0;
        }
    }

    private void ensureDefaultSubscription(Tenant tenant) {
        if (subscriptionRepo.existsByTenantId(tenant.getId())) return;
        TenantSubscription sub = subscriptionRepo.save(TenantSubscription.builder()
            .tenantId(tenant.getId())
            .planType(PlanType.ENTERPRISE)
            .status(TenantStatus.ACTIVE)
            .startDate(java.time.Instant.now())
            .endDate(null)  // lifetime for default tenant
            .notes("Default enterprise subscription for initial tenant")
            .build());
        log.info("Default ENTERPRISE subscription created: id={}", sub.getId());
    }

    private void seedMaintenanceTypes(Tenant tenant) {
        if (maintenanceTypeRepo.count() > 0) return;
        record MT(String name, String nameAr, ControlRoomType type) {}
        List<MT> defaults = List.of(
            // Residential / Civil
            new MT("Electrical",            "كهرباء",              ControlRoomType.residential),
            new MT("Plumbing",              "سباكة",               ControlRoomType.residential),
            new MT("HVAC",                  "تكييف وتهوية",         ControlRoomType.residential),
            new MT("Civil Works",           "أعمال مدنية",          ControlRoomType.residential),
            // Hospital general
            new MT("Electrical",            "كهرباء",              ControlRoomType.hospital),
            new MT("Plumbing",              "سباكة",               ControlRoomType.hospital),
            new MT("HVAC",                  "تكييف وتهوية",         ControlRoomType.hospital),
            new MT("Civil Works",           "أعمال مدنية",          ControlRoomType.hospital),
            // IT
            new MT("Printer",               "طابعة",               ControlRoomType.it),
            new MT("Network",               "شبكة",                ControlRoomType.it),
            new MT("Software",              "برمجيات",             ControlRoomType.it),
            new MT("Devices",               "أجهزة",               ControlRoomType.it),
            // Medical
            new MT("Equipment Failure",     "عطل معدات طبية",      ControlRoomType.medical),
            new MT("Preventive Maintenance","صيانة وقائية",         ControlRoomType.medical),
            new MT("Calibration",           "معايرة",              ControlRoomType.medical)
        );
        defaults.forEach(mt -> {
            MaintenanceType m = MaintenanceType.builder()
                .name(mt.name()).nameAr(mt.nameAr()).controlRoomType(mt.type()).isActive(true)
                .build();
            m.setTenantId(tenant.getId());
            maintenanceTypeRepo.save(m);
        });
        log.info("Seeded {} default maintenance types", defaults.size());
    }

    private void createAdminIfAbsent() {
        if (userRepo.existsByUsernameAndDeletedAtIsNull("admin")) {
            log.info("Admin account already exists — skipping seed");
            return;
        }

        User admin = User.builder()
            .username("admin")
            .email("admin@smms.local")
            .passwordHash(passwordEncoder.encode("Admin@1234"))
            .fullName("System Administrator")
            .fullNameAr("مدير النظام")
            .role(UserRole.system_admin)
            .isActive(true)
            .build();
        // tenantId remains null — platform admin has no tenant restriction

        userRepo.save(admin);
        log.info("=================================================");
        log.info("  Default admin account created:");
        log.info("  Username : admin");
        log.info("  Please change the password after first login!");
        log.info("=================================================");
    }
}
