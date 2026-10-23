package com.smms.service;

import com.smms.dto.request.UpdateUserRequest;
import com.smms.entity.*;
import com.smms.enums.UserRole;
import com.smms.mapper.UserMapper;
import com.smms.repository.*;
import com.smms.security.TenantSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceScopeTest {

    private TenantSecurityService tenantSecurity;
    private UserRepository userRepo;
    private UserService service;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        tenantSecurity = mock(TenantSecurityService.class);
        service = new UserService(userRepo, mock(UserControlRoomAccessRepository.class),
            mock(PasswordEncoder.class), mock(UserMapper.class), mock(PlanEnforcementService.class),
            tenantSecurity);
        when(userRepo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    private User user(UserRole role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setUsername(role.name());
        u.setRole(role);
        return u;
    }

    @Test
    void updateMeIgnoresRoleCenterAndRegion() {
        User me = user(UserRole.technician);
        when(tenantSecurity.requireUser(me.getId())).thenReturn(me);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole(UserRole.system_admin);
        req.setCenterId(UUID.randomUUID());
        req.setRegionId(UUID.randomUUID());
        req.setFullName("New Name");

        service.updateMe(me.getId(), req);

        assertThat(me.getRole()).isEqualTo(UserRole.technician);
        assertThat(me.getFullName()).isEqualTo("New Name");
        verify(tenantSecurity, never()).requireCenter(any());
        verify(tenantSecurity, never()).requireRegion(any());
    }

    @Test
    void hospitalSupervisorCannotGrantSystemAdmin() {
        User actor = user(UserRole.hospital_supervisor);
        User target = user(UserRole.technician);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole(UserRole.system_admin);

        assertThatThrownBy(() -> service.update(target.getId(), req, actor))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(target.getRole()).isEqualTo(UserRole.technician);
    }

    @Test
    void regionSupervisorCannotSelfPromoteViaAdminEndpoint() {
        User actor = user(UserRole.region_supervisor);
        when(tenantSecurity.requireUser(actor.getId())).thenReturn(actor);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole(UserRole.system_admin);

        assertThatThrownBy(() -> service.update(actor.getId(), req, actor))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void systemAdminCannotChangeOwnRole() {
        User actor = user(UserRole.system_admin);
        when(tenantSecurity.requireUser(actor.getId())).thenReturn(actor);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole(UserRole.technician);

        assertThatThrownBy(() -> service.update(actor.getId(), req, actor))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(actor.getRole()).isEqualTo(UserRole.system_admin);
    }

    @Test
    void systemAdminCanAssignRoleToOthers() {
        User actor = user(UserRole.system_admin);
        User target = user(UserRole.technician);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole(UserRole.hospital_supervisor);

        service.update(target.getId(), req, actor);
        assertThat(target.getRole()).isEqualTo(UserRole.hospital_supervisor);
    }

    @Test
    void hospitalSupervisorCannotMoveUserToForeignCenter() {
        User actor = user(UserRole.hospital_supervisor);
        Center own = new Center();
        own.setId(UUID.randomUUID());
        actor.setCenter(own);
        User target = user(UserRole.technician);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setCenterId(UUID.randomUUID());

        assertThatThrownBy(() -> service.update(target.getId(), req, actor))
            .isInstanceOf(AccessDeniedException.class);
    }
}
