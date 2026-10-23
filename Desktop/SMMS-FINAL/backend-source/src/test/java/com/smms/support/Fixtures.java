package com.smms.support;

import com.smms.entity.Center;
import com.smms.entity.ControlRoom;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.UserRole;

import java.util.UUID;

/** Small entity builders shared by the scope/tenant tests. */
public final class Fixtures {

    private Fixtures() {}

    public static Region region(UUID tenant) {
        Region r = new Region();
        r.setId(UUID.randomUUID());
        r.setTenantId(tenant);
        r.setCode("R" + r.getId().toString().substring(0, 6));
        return r;
    }

    public static Center center(Region region, UUID tenant) {
        Center c = new Center();
        c.setId(UUID.randomUUID());
        c.setRegion(region);
        c.setTenantId(tenant);
        return c;
    }

    public static ControlRoom room(Center center, UUID tenant) {
        ControlRoom cr = new ControlRoom();
        cr.setId(UUID.randomUUID());
        cr.setCenter(center);
        cr.setTenantId(tenant);
        return cr;
    }

    public static User user(UserRole role, Center center, Region region, UUID tenant) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setUsername(role.name() + "-" + u.getId().toString().substring(0, 4));
        u.setRole(role);
        u.setCenter(center);
        u.setRegion(region);
        u.setTenantId(tenant);
        u.setActive(true);
        return u;
    }
}
