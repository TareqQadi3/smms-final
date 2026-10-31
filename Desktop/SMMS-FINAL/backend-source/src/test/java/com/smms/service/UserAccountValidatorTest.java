package com.smms.service;

import com.smms.dto.request.CreateUserRequest;
import com.smms.dto.request.UpdateUserRequest;
import com.smms.entity.Center;
import com.smms.entity.ControlRoom;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.entity.UserControlRoomAccess;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.mapper.UserMapper;
import com.smms.repository.*;
import com.smms.security.AdminScopePolicy;
import com.smms.security.TenantSecurityService;
import com.smms.util.TenantContext;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.smms.support.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * M3-C: one validator for single creation and bulk import. The parity table pushes the same bad
 * input through UserService.create and through a bulk import row and expects the same message.
 */
class UserAccountValidatorTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final UUID tenantA = UUID.randomUUID();

    private UserRepository userRepo;
    private UserControlRoomAccessRepository accessRepo;
    private TenantSecurityService tenantSecurity;
    private CenterRepository centerRepo;
    private UserService userService;
    private BulkImportService bulk;
    private final UserAccountValidator validator = new UserAccountValidator();
    private Center centerA;
    private User platformAdmin;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        accessRepo = mock(UserControlRoomAccessRepository.class);
        tenantSecurity = mock(TenantSecurityService.class);
        centerRepo = mock(CenterRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("HASH");
        AdminScopePolicy policy = new AdminScopePolicy(mock(TenantRepository.class));
        userService = new UserService(userRepo, accessRepo, encoder, mock(UserMapper.class),
            mock(PlanEnforcementService.class), tenantSecurity, policy, validator);
        bulk = new BulkImportService(userRepo, centerRepo, encoder, policy, mock(PlanEnforcementService.class), validator);
        centerA = center(region(tenantA), tenantA);
        when(centerRepo.findByCodeIgnoreCaseAndDeletedAtIsNull("CA")).thenReturn(Optional.of(centerA));
        when(tenantSecurity.requireCenter(centerA.getId())).thenReturn(centerA);
        when(userRepo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        platformAdmin = user(UserRole.system_admin, null, null, null);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── parity table ────────────────────────────────────────

    /** username, password, email, phone, name, departmentName, departmentEmail, expected message part */
    static Stream<Arguments> badInputs() {
        String ok = "Passw0rd!";
        return Stream.of(
            Arguments.of("ab", ok, null, null, "Full", null, null, "username must be 3-100"),
            Arguments.of("bad name!", ok, null, null, "Full", null, null, "username must be 3-100"),
            Arguments.of("x".repeat(101), ok, null, null, "Full", null, null, "username must be 3-100"),
            Arguments.of("good.user", "short", null, null, "Full", null, null, "password must be 8-128"),
            Arguments.of("good.user", "p".repeat(129), null, null, "Full", null, null, "password must be 8-128"),
            Arguments.of("good.user", ok, "not-an-email", null, "Full", null, null, "email is not a valid address"),
            Arguments.of("good.user", ok, null, "0".repeat(21), "Full", null, null, "phone must be at most 20"),
            Arguments.of("good.user", ok, null, null, "N".repeat(201), null, null, "name must be at most 200"),
            Arguments.of("good.user", ok, null, null, "Full", "Emergency", "bad-mail", "departmentEmail is not a valid address")
        );
    }

    @ParameterizedTest
    @MethodSource("badInputs")
    void sameBadInputGivesTheSameMessageInSingleAndBulk(String username, String password, String email, String phone,
            String name, String deptName, String deptEmail, String expected) throws Exception {
        boolean department = deptEmail != null;
        UserRole role = department ? UserRole.department : UserRole.resident;

        CreateUserRequest req = new CreateUserRequest();
        req.setUsername(username);
        req.setPassword(password);
        req.setEmail(email);
        req.setPhone(phone);
        req.setFullName(name);
        req.setRole(role);
        req.setCenterId(centerA.getId());
        req.setDepartmentName(deptName);
        req.setDepartmentEmail(deptEmail);
        assertThatThrownBy(() -> userService.create(req, platformAdmin))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(expected);

        String[] row = department
            ? new String[]{username, password, deptName, "", deptEmail, "", email == null ? "" : email, phone == null ? "" : phone, "CA"}
            : new String[]{username, password, name, "", email == null ? "" : email, phone == null ? "" : phone, "", "", "", "CA"};
        var result = department ? bulk.importDepartments(file(row), platformAdmin) : bulk.importResidents(file(row), platformAdmin);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).anyMatch(e -> e.contains(expected));
        verify(userRepo, never()).saveAllAndFlush(any());
        verify(userRepo, never()).save(any());
    }

    @Test
    void departmentWithoutNameIsRejectedInSingleAndBulk() throws Exception {
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername("dept.user");
        req.setPassword("Passw0rd!");
        req.setFullName("Dept Contact");
        req.setRole(UserRole.department);
        req.setCenterId(centerA.getId());
        assertThatThrownBy(() -> userService.create(req, platformAdmin))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("departmentName is required");

        var result = bulk.importDepartments(file(new String[]{"dept.user", "Passw0rd!", "", "", "", "", "", "", "CA"}),
            platformAdmin);
        assertThat(result.errors()).containsExactly("Row 2: departmentName is required");
    }

    @Test
    void departmentWithNameAndCenterIsAccepted() {
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername("dept.user");
        req.setPassword("Passw0rd!");
        req.setFullName("Dept Contact");
        req.setRole(UserRole.department);
        req.setCenterId(centerA.getId());
        req.setDepartmentName("Emergency");
        when(tenantSecurity.requireCenter(centerA.getId())).thenReturn(centerA);
        userService.create(req, platformAdmin);
        verify(userRepo).save(any(User.class));
    }

    @Test
    void departmentWithoutCenterIsRejected() {
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername("dept.user");
        req.setPassword("Passw0rd!");
        req.setFullName("Dept Contact");
        req.setRole(UserRole.department);
        req.setDepartmentName("Emergency");
        assertThatThrownBy(() -> userService.create(req, platformAdmin))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Center is required");
    }

    @Test
    void usernameIsTrimmedBeforeValidationAndDuplicateCheck() {
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername("  res.one  ");
        req.setPassword("Passw0rd!");
        req.setFullName("Res One");
        req.setRole(UserRole.resident);
        req.setCenterId(centerA.getId());
        userService.create(req, platformAdmin);
        verify(userRepo).existsByUsernameIgnoreCaseAndDeletedAtIsNull("res.one");
    }

    // ── update path ─────────────────────────────────────────

    @Test
    void roleChangeToDepartmentWithoutDepartmentNameIs400() {
        User target = user(UserRole.technician, centerA, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole(UserRole.department);
        assertThatThrownBy(() -> userService.update(target.getId(), req, platformAdmin))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("departmentName is required");
        assertThat(target.getRole()).isEqualTo(UserRole.technician);
    }

    @Test
    void roleChangeToDepartmentWithNameIsAppliedAndNameStored() {
        User target = user(UserRole.technician, centerA, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setRole(UserRole.department);
        req.setDepartmentName("Radiology");
        userService.update(target.getId(), req, platformAdmin);
        assertThat(target.getRole()).isEqualTo(UserRole.department);
        assertThat(target.getDepartmentName()).isEqualTo("Radiology");
    }

    @Test
    void updateAppliesTheSameContactRules() {
        User target = user(UserRole.technician, centerA, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        UpdateUserRequest badMail = new UpdateUserRequest();
        badMail.setEmail("nope");
        assertThatThrownBy(() -> userService.update(target.getId(), badMail, platformAdmin))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("email is not a valid address");
        UpdateUserRequest badPhone = new UpdateUserRequest();
        badPhone.setPhone("9".repeat(21));
        assertThatThrownBy(() -> userService.update(target.getId(), badPhone, platformAdmin))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passwordLengthCapAppliesToChangeAndReset() {
        User target = user(UserRole.technician, centerA, null, tenantA);
        when(tenantSecurity.requireUser(target.getId())).thenReturn(target);
        assertThatThrownBy(() -> userService.resetPassword(target.getId(), "p".repeat(129)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> userService.resetPassword(target.getId(), "short"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── control-room grants ─────────────────────────────────

    @Test
    void departmentCannotGetARoomOfAnotherCenterEvenFromSystemAdmin() {
        Center other = center(centerA.getRegion(), tenantA);
        ControlRoom foreign = room(other, tenantA);
        User dept = user(UserRole.department, centerA, null, tenantA);
        when(tenantSecurity.requireUser(dept.getId())).thenReturn(dept);
        when(tenantSecurity.requireControlRoom(foreign.getId())).thenReturn(foreign);
        assertThatThrownBy(() -> userService.grantControlRoomAccess(dept.getId(), foreign.getId(), platformAdmin))
            .isInstanceOf(IllegalArgumentException.class);
        verify(accessRepo, never()).save(any());
    }

    @Test
    void departmentGetsRoomOfOwnCenter() {
        ControlRoom own = room(centerA, tenantA);
        User dept = user(UserRole.department, centerA, null, tenantA);
        when(tenantSecurity.requireUser(dept.getId())).thenReturn(dept);
        when(tenantSecurity.requireControlRoom(own.getId())).thenReturn(own);
        userService.grantControlRoomAccess(dept.getId(), own.getId(), platformAdmin);
        verify(accessRepo).save(any(UserControlRoomAccess.class));
    }

    @Test
    void validatorGrantRuleByActor() {
        Center other = center(centerA.getRegion(), tenantA);
        ControlRoom foreign = room(other, tenantA);
        User supervisor = user(UserRole.hospital_supervisor, centerA, null, tenantA);
        // non-admin actor, non-department user: 403 as before
        assertThatThrownBy(() -> validator.assertGrantAllowed(supervisor, UserRole.technician, centerA, foreign))
            .isInstanceOf(AccessDeniedException.class);
        // system_admin may still cross centers for ordinary roles (unchanged)
        validator.assertGrantAllowed(platformAdmin, UserRole.technician, centerA, foreign);
        // a user without a center is not restricted (legacy, unchanged)
        validator.assertGrantAllowed(supervisor, UserRole.technician, null, foreign);
    }

    @Test
    void movingAUserRevokesGrantsOfTheOldCenterOnly() {
        Center target = center(centerA.getRegion(), tenantA);
        ControlRoom oldRoom = room(centerA, tenantA);
        ControlRoom newRoom = room(target, tenantA);
        User tech = user(UserRole.technician, centerA, null, tenantA);
        when(tenantSecurity.requireUser(tech.getId())).thenReturn(tech);
        when(tenantSecurity.requireCenter(target.getId())).thenReturn(target);
        when(accessRepo.findByUserId(tech.getId())).thenReturn(List.of(
            UserControlRoomAccess.builder().user(tech).controlRoom(oldRoom).build(),
            UserControlRoomAccess.builder().user(tech).controlRoom(newRoom).build()));
        UpdateUserRequest req = new UpdateUserRequest();
        req.setCenterId(target.getId());
        userService.update(tech.getId(), req, platformAdmin);
        verify(accessRepo).deleteByUserIdAndControlRoomId(tech.getId(), oldRoom.getId());
        verify(accessRepo, never()).deleteByUserIdAndControlRoomId(tech.getId(), newRoom.getId());
    }

    @Test
    void updateWithoutCenterChangeKeepsGrants() {
        ControlRoom own = room(centerA, tenantA);
        User tech = user(UserRole.technician, centerA, null, tenantA);
        when(tenantSecurity.requireUser(tech.getId())).thenReturn(tech);
        UpdateUserRequest req = new UpdateUserRequest();
        req.setPhone("0500000000");
        userService.update(tech.getId(), req, platformAdmin);
        verify(accessRepo, never()).deleteByUserIdAndControlRoomId(any(), any());
    }

    // ── validator unit rules ────────────────────────────────

    @Test
    void validatorLinkRulesForRolesAndRegion() {
        Region r = region(tenantA);
        Center c = center(r, tenantA);
        assertThat(validator.validateLinks(UserRole.region_supervisor, null, null, null)).isNotEmpty();
        assertThat(validator.validateLinks(UserRole.region_supervisor, null, r, null)).isEmpty();
        assertThat(validator.validateLinks(UserRole.technician, null, null, null)).isNotEmpty();
        assertThat(validator.validateLinks(UserRole.technician, c, null, null)).isEmpty();
        assertThat(validator.validateLinks(UserRole.technician, c, region(tenantA), null))
            .contains("Center does not belong to the given region");
        assertThat(validator.validateLinks(UserRole.other, null, null, null)).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────

    private static MockMultipartFile file(String[] row) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("S");
            sh.createRow(0);
            Row r = sh.createRow(1);
            for (int i = 0; i < row.length; i++) r.createCell(i).setCellValue(row[i] == null ? "" : row[i]);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new MockMultipartFile("file", "import.xlsx", XLSX, out.toByteArray());
        }
    }
}
