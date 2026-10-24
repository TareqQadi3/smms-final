package com.smms.inventory;

import com.smms.entity.Center;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.inventory.controller.InventoryController;
import com.smms.inventory.service.InventoryAccess;
import com.smms.inventory.service.InventoryAccess.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Role matrix of the inventory module (plan 2.11), pinned on the real controller and policy. */
class InventoryAccessTest {

    private final InventoryAccess access = new InventoryAccess();

    private static User user(UserRole role, UUID center, UUID region) {
        User u = new User();
        u.setRole(role);
        if (center != null) { Center c = new Center(); c.setId(center); u.setCenter(c); }
        if (region != null) { Region r = new Region(); r.setId(region); u.setRegion(r); }
        return u;
    }

    @Test void scopeByRole() {
        UUID c = UUID.randomUUID(), r = UUID.randomUUID();
        assertThat(access.scope(user(UserRole.system_admin, null, null)).kind()).isEqualTo(Kind.ALL);
        assertThat(access.scope(user(UserRole.region_supervisor, null, r)).kind()).isEqualTo(Kind.REGION);
        assertThat(access.scope(user(UserRole.region_supervisor, null, null)).kind()).isEqualTo(Kind.NONE);
        for (UserRole role : List.of(UserRole.warehouse, UserRole.maintenance_manager, UserRole.technician, UserRole.control_room)) {
            assertThat(access.scope(user(role, c, null)).kind()).as(role.name()).isEqualTo(Kind.CENTER);
            assertThat(access.scope(user(role, null, null)).kind()).as(role + " without center").isEqualTo(Kind.NONE);
        }
        for (UserRole role : List.of(UserRole.resident, UserRole.department, UserRole.other)) {
            assertThat(access.scope(user(role, c, r)).kind()).as(role.name()).isEqualTo(Kind.NONE);
        }
    }

    @Test void centerScope() {
        UUID c1 = UUID.randomUUID(), c2 = UUID.randomUUID();
        Center center1 = new Center(); center1.setId(c1);
        Center center2 = new Center(); center2.setId(c2);
        User wh = user(UserRole.warehouse, c1, null);
        assertThat(access.centerInScope(wh, center1)).isTrue();
        assertThat(access.centerInScope(wh, center2)).isFalse();
        assertThat(access.centerInScope(user(UserRole.other, c1, null), center1)).isFalse();
    }

    @Test void whoMayCorrectAndOperate() {
        assertThat(InventoryAccess.CORRECTORS).containsExactlyInAnyOrder(UserRole.system_admin, UserRole.maintenance_manager);
        assertThat(InventoryAccess.OPERATORS).containsExactlyInAnyOrder(UserRole.system_admin, UserRole.maintenance_manager, UserRole.warehouse);
        assertThat(InventoryAccess.CORRECTORS).doesNotContain(UserRole.warehouse);
        assertThat(InventoryAccess.STOCK_READERS).doesNotContain(UserRole.technician, UserRole.control_room, UserRole.resident, UserRole.department, UserRole.other);
        assertThat(InventoryAccess.CATALOG_READERS).contains(UserRole.technician, UserRole.control_room).doesNotContain(UserRole.other, UserRole.resident);
        assertThatThrownBy(() -> access.requireRole(user(UserRole.warehouse, UUID.randomUUID(), null), InventoryAccess.CORRECTORS))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test void controllerRolesMatchTheMatrix() {
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        Set<String> readers = names(InventoryAccess.STOCK_READERS), catalog = names(InventoryAccess.CATALOG_READERS);
        Set<String> movement = new TreeSet<>(readers); movement.add("technician");
        expected.put("uoms", readers); expected.put("warehouses", readers); expected.put("warehouse", readers);
        expected.put("items", catalog); expected.put("item", catalog); expected.put("stockCard", readers);
        expected.put("balances", readers); expected.put("lowStock", readers); expected.put("movements", movement);
        expected.put("createUom", names(InventoryAccess.UOM_WRITERS)); expected.put("updateUom", names(InventoryAccess.UOM_WRITERS));
        expected.put("createWarehouse", names(InventoryAccess.WAREHOUSE_WRITERS)); expected.put("updateWarehouse", names(InventoryAccess.WAREHOUSE_WRITERS));
        expected.put("createItem", names(InventoryAccess.CATALOG_WRITERS)); expected.put("updateItem", names(InventoryAccess.CATALOG_WRITERS));
        expected.put("setMinQty", names(InventoryAccess.CATALOG_WRITERS));
        expected.put("receipt", names(InventoryAccess.OPERATORS));
        expected.put("adjust", names(InventoryAccess.CORRECTORS)); expected.put("reverse", names(InventoryAccess.CORRECTORS));
        Pattern role = Pattern.compile("'([a-z_]+)'");
        int seen = 0;
        for (Method m : InventoryController.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPrivate(m.getModifiers())) continue;
            PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
            assertThat(pa).as(m.getName()).isNotNull();
            Set<String> got = new TreeSet<>();
            Matcher mt = role.matcher(pa.value());
            while (mt.find()) got.add(mt.group(1));
            assertThat(got).as(m.getName()).isEqualTo(new TreeSet<>(expected.get(m.getName())));
            seen++;
        }
        assertThat(seen).isEqualTo(expected.size());
    }

    private static Set<String> names(Set<UserRole> roles) {
        Set<String> s = new TreeSet<>();
        roles.forEach(r -> s.add(r.name()));
        return s;
    }
}
