package com.smms.assets;

import com.smms.entity.*;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssetAccessPolicyTest {

    private final AssetAccessPolicy policy = new AssetAccessPolicy();
    private final UUID tenant = UUID.randomUUID();
    private final Region region = Fixtures.region(tenant);
    private final Center center = Fixtures.center(region, tenant);
    private final Center otherCenter = Fixtures.center(region, tenant);

    private User user(UserRole role) { return Fixtures.user(role, center, region, tenant); }

    @Test
    void readScopePerRole() {
        assertThat(policy.readScope(user(UserRole.system_admin)).all()).isTrue();
        assertThat(policy.readScope(user(UserRole.region_supervisor)).regionId()).isEqualTo(region.getId());
        for (UserRole r : new UserRole[] {UserRole.hospital_supervisor, UserRole.maintenance_manager, UserRole.electrical_manager,
            UserRole.operating_company_manager, UserRole.control_room, UserRole.warehouse, UserRole.technician}) {
            AssetAccessPolicy.Scope s = policy.readScope(user(r));
            assertThat(s.centerId()).as(r.name()).isEqualTo(center.getId());
            assertThat(s.all()).isFalse();
        }
    }

    @Test
    void residentOtherAndCenterlessUsersSeeNothing() {
        assertThat(policy.readScope(user(UserRole.resident)).none()).isTrue();
        assertThat(policy.readScope(user(UserRole.other)).none()).isTrue();
        assertThat(policy.readScope(Fixtures.user(UserRole.maintenance_manager, null, null, tenant)).none()).isTrue();
        assertThat(policy.readScope(null).none()).isTrue();
    }

    @Test
    void departmentUserIsLimitedToTheOwnDepartment() {
        User de = user(UserRole.department);
        assertThat(policy.readScope(de).none()).isTrue(); // no department linked
        Department d = new Department();
        d.setId(UUID.randomUUID());
        de.setDepartment(d);
        AssetAccessPolicy.Scope s = policy.readScope(de);
        assertThat(s.departmentId()).isEqualTo(d.getId());
        assertThat(s.centerId()).isNull();
    }

    @Test
    void writersAreSaHsMmEmInTheirOwnCenter() {
        for (UserRole r : UserRole.values()) {
            boolean expected = r == UserRole.system_admin || r == UserRole.hospital_supervisor
                || r == UserRole.maintenance_manager || r == UserRole.electrical_manager;
            assertThat(policy.canWrite(user(r))).as(r.name()).isEqualTo(expected);
        }
        User mm = user(UserRole.maintenance_manager);
        assertThat(policy.centerWritable(mm, center.getId())).isTrue();
        assertThat(policy.centerWritable(mm, otherCenter.getId())).isFalse();
        assertThat(policy.centerWritable(user(UserRole.system_admin), otherCenter.getId())).isTrue();
        assertThat(policy.centerWritable(user(UserRole.region_supervisor), center.getId())).isFalse();
    }

    @Test
    void foreignCenterAssetIs404ForAWriterAndRoleGateIs403() {
        Asset foreign = Asset.builder().center(otherCenter).build();
        assertThatThrownBy(() -> policy.assertWritable(user(UserRole.maintenance_manager), foreign))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> policy.assertWritable(user(UserRole.control_room), foreign))
            .isInstanceOf(AccessDeniedException.class);
        Asset own = Asset.builder().center(center).build();
        policy.assertWritable(user(UserRole.hospital_supervisor), own);
    }
}
