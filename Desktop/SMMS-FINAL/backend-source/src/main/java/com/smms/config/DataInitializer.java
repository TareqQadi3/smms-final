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
     * Backfills tenant_id for every row that was created before multi-tenancy was introduced.
     * Safe to run multiple times — only updates NULL rows.
     */
    private void backfillTenantId(java.util.UUID tenantId) {
        String[] tables = {
            "users", "regions", "centers", "control_rooms", "work_orders",
            "maintenance_types", "ticket_history", "notifications", "attachments",
            "departments", "assets", "inventory_items", "inventory_movements"
        };
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            int total = 0;
            for (String table : tables) {
                try {
                    // For users table: exclude system_admin (platform-level, no tenant)
                    String where = table.equals("users")
                        ? "WHERE tenant_id IS NULL AND role::text != 'system_admin'"
                        : "WHERE tenant_id IS NULL";
                    int rows = stmt.executeUpdate(
                        "UPDATE " + table + " SET tenant_id = '" + tenantId + "' " + where);
                    total += rows;
                } catch (Exception e) {
                    log.debug("Backfill skip {}: {}", table, e.getMessage());
                }
            }
            // Ensure existing system_admin users always have null tenantId
            try {
                stmt.executeUpdate("UPDATE users SET tenant_id = NULL WHERE role::text = 'system_admin'");
            } catch (Exception e) {
                log.debug("Admin tenantId clear skip: {}", e.getMessage());
            }
            if (total > 0) log.info("Backfilled tenant_id for {} rows across {} tables", total, tables.length);
        } catch (Exception e) {
            log.warn("Tenant backfill failed: {}", e.getMessage());
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
