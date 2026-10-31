package com.smms.it;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Plain-SQL fixture rows for database tests (works on the V12 schema and on the latest schema). */
public final class DbFixtures {

    private final JdbcTemplate jdbc;
    public final String tag = UUID.randomUUID().toString().substring(0, 8);

    public DbFixtures(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Hierarchy(UUID tenant, UUID region, UUID center, UUID controlRoom) {}

    public Hierarchy hierarchy() {
        UUID tenant = UUID.randomUUID(), region = UUID.randomUUID(), center = UUID.randomUUID(), room = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug, is_active) VALUES (?, ?, ?, true)",
            tenant, "T-" + tag + "-" + tenant.toString().substring(0, 4), "t" + tenant.toString().substring(0, 12));
        jdbc.update("INSERT INTO regions (id, tenant_id, name, name_ar, code) VALUES (?, ?, 'R', 'R', ?)",
            region, tenant, "R" + region.toString().substring(0, 8));
        jdbc.update("INSERT INTO centers (id, tenant_id, region_id, name, name_ar, code) VALUES (?, ?, ?, 'C', 'C', ?)",
            center, tenant, region, "C" + center.toString().substring(0, 8));
        jdbc.update("INSERT INTO control_rooms (id, tenant_id, center_id, type, name, name_ar) "
            + "VALUES (?, ?, ?, 'hospital'::control_room_type, 'CR', 'CR')", room, tenant, center);
        return new Hierarchy(tenant, region, center, room);
    }

    public UUID user(Hierarchy h, String role) {
        UUID id = UUID.randomUUID();
        String name = role + "-" + id.toString().substring(0, 8);
        jdbc.update("INSERT INTO users (id, tenant_id, region_id, center_id, username, email, password_hash, full_name, role, is_active) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'x', ?, ?::user_role, true)",
            id, h.tenant(), h.region(), h.center(), name, name + "@t.invalid", name, role);
        return id;
    }

    public UUID department(Hierarchy h, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, tenant_id, center_id, name, name_ar, code) VALUES (?, ?, ?, 'D', 'D', ?)",
            id, h.tenant(), h.center(), code);
        return id;
    }

    public UUID asset(Hierarchy h) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO assets (id, tenant_id, center_id, name, name_ar) VALUES (?, ?, ?, 'A', 'A')",
            id, h.tenant(), h.center());
        return id;
    }

    public UUID workOrder(Hierarchy h, UUID requester, String status, String priority) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO work_orders (id, tenant_id, center_id, control_room_id, status, priority, sub_type, title, "
            + "requested_by_id, created_by_role) VALUES (?, ?, ?, ?, ?::ticket_status, ?::ticket_priority, "
            + "'electrical'::sub_type, 'fixture', ?, 'control_room'::user_role)",
            id, h.tenant(), h.center(), h.controlRoom(), status, priority, requester);
        return id;
    }
}
