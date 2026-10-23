package com.smms.service;

import com.smms.entity.Center;
import com.smms.entity.Region;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.PlanLimitExceededException;
import com.smms.repository.CenterRepository;
import com.smms.repository.TenantRepository;
import com.smms.repository.UserRepository;
import com.smms.security.AdminScopePolicy;
import com.smms.util.TenantContext;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.smms.support.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BulkImportServiceTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final UUID tA = UUID.randomUUID();
    private final UUID tB = UUID.randomUUID();

    private UserRepository userRepo;
    private CenterRepository centerRepo;
    private PlanEnforcementService plan;
    private BulkImportService service;
    private Center centerA;
    private Center centerB;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        centerRepo = mock(CenterRepository.class);
        plan = mock(PlanEnforcementService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("HASH");
        service = new BulkImportService(userRepo, centerRepo, encoder,
            new AdminScopePolicy(mock(TenantRepository.class)), plan);
        centerA = center(region(tA), tA);
        centerB = center(region(tB), tB);
        when(centerRepo.findByTenantIdAndCodeIgnoreCaseAndDeletedAtIsNull(tA, "CA")).thenReturn(Optional.of(centerA));
        when(centerRepo.findByCodeIgnoreCaseAndDeletedAtIsNull("CA")).thenReturn(Optional.of(centerA));
        when(centerRepo.findByCodeIgnoreCaseAndDeletedAtIsNull("CB")).thenReturn(Optional.of(centerB));
        when(centerRepo.findByCodeIgnoreCaseAndDeletedAtIsNull("cb")).thenReturn(Optional.of(centerB));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private User platformAdmin() { return user(UserRole.system_admin, null, null, null); }

    private static String[] resident(String username, String password, String centerCode) {
        return new String[]{username, password, "Full Name", "", "", "", "", "", "", centerCode};
    }

    private static byte[] workbook(String[] header, List<String[]> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("S");
            Row h = sh.createRow(0);
            for (int i = 0; i < header.length; i++) h.createCell(i).setCellValue(header[i]);
            for (int r = 0; r < rows.size(); r++) {
                Row row = sh.createRow(r + 1);
                for (int i = 0; i < rows.get(r).length; i++) row.createCell(i).setCellValue(rows.get(r)[i]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static List<String[]> rows(String[]... r) { return new ArrayList<>(java.util.Arrays.asList(r)); }

    private MockMultipartFile file(List<String[]> rows) throws Exception {
        return new MockMultipartFile("file", "residents.xlsx", XLSX, workbook(new String[10], rows));
    }

    @SuppressWarnings("unchecked")
    private List<User> saved() {
        ArgumentCaptor<List<User>> c = ArgumentCaptor.forClass(List.class);
        verify(userRepo).saveAllAndFlush(c.capture());
        return c.getValue();
    }

    // ── happy paths ─────────────────────────────────────────

    @Test
    void usersGetTenantAndCenterFromTheCenterRowAndPasswordsAreHashed() throws Exception {
        var result = service.importResidents(file(rows(
            resident("res.one", "Passw0rd!", "CA"), resident("res.two", "Passw0rd!", "cb"))), platformAdmin());

        assertThat(result.success()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        List<User> users = saved();
        assertThat(users).extracting(User::getTenantId).containsExactly(tA, tB);
        assertThat(users).extracting(User::getRole).containsOnly(UserRole.resident);
        assertThat(users).extracting(User::getPasswordHash).containsOnly("HASH");
        assertThat(users).allSatisfy(u -> assertThat(u.isActive()).isTrue());
        assertThat(users.get(0).getEmail()).isEqualTo("res.one@smms.local");
        verify(plan).checkUserLimit(tA, 1);
        verify(plan).checkUserLimit(tB, 1);
    }

    @Test
    void tenantAdminUsesTenantScopedLookupOnly() throws Exception {
        TenantContext.set(tA);
        var result = service.importResidents(file(rows(resident("res.one", "Passw0rd!", "CA"))), platformAdmin());
        assertThat(result.success()).isEqualTo(1);
        verify(centerRepo, never()).findByCodeIgnoreCaseAndDeletedAtIsNull(anyString());
        assertThat(saved().get(0).getTenantId()).isEqualTo(tA);
    }

    @Test
    void hospitalSupervisorMayImportIntoOwnCenter() throws Exception {
        TenantContext.set(tA);
        User actor = user(UserRole.hospital_supervisor, centerA, null, tA);
        assertThat(service.importResidents(file(rows(resident("res.one", "Passw0rd!", "CA"))), actor).success()).isEqualTo(1);
    }

    @Test
    void numericCellsKeepTheirDigits() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("S");
            sh.createRow(0).createCell(0).setCellValue("h");
            Row r = sh.createRow(1);
            String[] v = resident("res.one", "Passw0rd!", "CA");
            for (int i = 0; i < v.length; i++) r.createCell(i).setCellValue(v[i]);
            r.getCell(5).setCellValue(966501234567.0);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            bytes = out.toByteArray();
        }
        service.importResidents(new MockMultipartFile("file", "r.xlsx", XLSX, bytes), platformAdmin());
        assertThat(saved().get(0).getPhone()).isEqualTo("966501234567");
    }

    @Test
    void departmentsAreImportedWithTheirOwnColumns() throws Exception {
        String[] row = {"dept01", "Passw0rd!", "Emergency", "Dr. Salem", "dept@x.com", "1234", "", "", "CA"};
        var f = new MockMultipartFile("file", "d.xlsx", XLSX, workbook(new String[9], rows(row)));
        var result = service.importDepartments(f, platformAdmin());
        assertThat(result.success()).isEqualTo(1);
        User u = saved().get(0);
        assertThat(u.getRole()).isEqualTo(UserRole.department);
        assertThat(u.getDepartmentName()).isEqualTo("Emergency");
        assertThat(u.getFullName()).isEqualTo("Emergency");
        assertThat(u.getTenantId()).isEqualTo(tA);
    }

    // ── scope and tenant (denied) ───────────────────────────

    @Test
    void centerOfAnotherTenantIsNotFoundForTenantAdmin() throws Exception {
        TenantContext.set(tA);
        // CB exists globally but the tenant-scoped lookup does not return it
        var result = service.importResidents(file(rows(resident("res.one", "Passw0rd!", "CB"))), platformAdmin());
        assertThat(result.success()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0)).contains("center code 'CB' not found");
        verify(userRepo, never()).saveAllAndFlush(any());
    }

    @Test
    void hospitalSupervisorCannotImportIntoAnotherCenter() throws Exception {
        TenantContext.set(tA);
        Center other = center(centerA.getRegion(), tA);
        when(centerRepo.findByTenantIdAndCodeIgnoreCaseAndDeletedAtIsNull(tA, "CX")).thenReturn(Optional.of(other));
        User actor = user(UserRole.hospital_supervisor, centerA, null, tA);

        var result = service.importResidents(file(rows(resident("res.one", "Passw0rd!", "CX"))), actor);

        assertThat(result.success()).isZero();
        assertThat(result.errors().get(0)).contains("not found"); // same wording as a missing code
        verify(userRepo, never()).saveAllAndFlush(any());
    }

    @Test
    void regionSupervisorOnlyImportsIntoOwnRegion() throws Exception {
        TenantContext.set(tA);
        Region own = centerA.getRegion();
        Center foreign = center(region(tA), tA);
        when(centerRepo.findByTenantIdAndCodeIgnoreCaseAndDeletedAtIsNull(tA, "CF")).thenReturn(Optional.of(foreign));
        User actor = user(UserRole.region_supervisor, null, own, tA);

        assertThat(service.importResidents(file(rows(resident("a.one", "Passw0rd!", "CA"))), actor).success()).isEqualTo(1);
        reset(userRepo);
        var denied = service.importResidents(file(rows(resident("b.one", "Passw0rd!", "CF"))), actor);
        assertThat(denied.success()).isZero();
        verify(userRepo, never()).saveAllAndFlush(any());
    }

    @Test
    void rolesThatCannotCreateUsersCannotImport() throws Exception {
        TenantContext.set(tA);
        User actor = user(UserRole.control_room, centerA, null, tA);
        assertThatThrownBy(() -> service.importResidents(file(rows(resident("res.one", "Passw0rd!", "CA"))), actor))
            .isInstanceOf(AccessDeniedException.class);
        verify(userRepo, never()).saveAllAndFlush(any());
    }

    // ── validation and all-or-nothing ───────────────────────

    @Test
    void anyInvalidRowMeansNothingIsSaved() throws Exception {
        var result = service.importResidents(file(rows(
            resident("good.one", "Passw0rd!", "CA"),
            resident("bad.one", "short", "CA"),
            resident("good.two", "Passw0rd!", "CA"))), platformAdmin());

        assertThat(result.success()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).startsWith("Row 3:").contains("password");
        verify(userRepo, never()).saveAllAndFlush(any());
        verify(userRepo, never()).save(any());
        verifyNoInteractions(plan);
    }

    @Test
    void errorsNeverEchoPasswords() throws Exception {
        var result = service.importResidents(file(rows(resident("bad one", "SecretPw1", "NOPE"))), platformAdmin());
        assertThat(result.errors()).isNotEmpty();
        assertThat(String.join("|", result.errors())).doesNotContain("SecretPw1");
    }

    @Test
    void duplicateUsernameInsideTheFileIsReported() throws Exception {
        var result = service.importResidents(file(rows(
            resident("dup.user", "Passw0rd!", "CA"), resident("dup.user", "Passw0rd!", "CA"))), platformAdmin());
        assertThat(result.success()).isZero();
        assertThat(result.errors().get(0)).startsWith("Row 3:").contains("more than once");
    }

    @Test
    void existingUsernameAndEmailAreReported() throws Exception {
        when(userRepo.existsByUsernameAndDeletedAtIsNull("taken")).thenReturn(true);
        when(userRepo.existsByEmailAndDeletedAtIsNull("used@x.com")).thenReturn(true);
        String[] mailRow = resident("mail.user", "Passw0rd!", "CA");
        mailRow[4] = "used@x.com";
        var result = service.importResidents(file(rows(resident("taken", "Passw0rd!", "CA"), mailRow)), platformAdmin());
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.errors().get(0)).contains("already exists");
        assertThat(result.errors().get(1)).contains("email");
    }

    @Test
    void invalidUsernameEmailAndMissingFieldsAreReported() throws Exception {
        String[] badMail = resident("ok.user", "Passw0rd!", "CA");
        badMail[4] = "not-an-email";
        String[] missing = {"", "", "", "", "", "", "", "", "", ""};
        missing[9] = "CA";
        var result = service.importResidents(file(rows(
            resident("bad name!", "Passw0rd!", "CA"), badMail, missing)), platformAdmin());
        assertThat(result.failed()).isEqualTo(3);
        verify(userRepo, never()).saveAllAndFlush(any());
    }

    @Test
    void missingCenterCodeIsReported() throws Exception {
        var result = service.importResidents(file(rows(resident("res.one", "Passw0rd!", ""))), platformAdmin());
        assertThat(result.errors().get(0)).contains("centerCode is required");
    }

    @Test
    void htmlInNamesIsStripped() throws Exception {
        String[] row = resident("res.one", "Passw0rd!", "CA");
        row[2] = "<script>alert(1)</script>Ali";
        service.importResidents(file(rows(row)), platformAdmin());
        assertThat(saved().get(0).getFullName()).isEqualTo("alert(1)Ali");
    }

    @Test
    void tooManyRowsIsRejectedBeforeAnyLookup() throws Exception {
        ReflectionTestUtils.setField(service, "maxRows", 2);
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) rows.add(resident("user.n" + i, "Passw0rd!", "CA"));
        assertThatThrownBy(() -> service.importResidents(file(rows), platformAdmin()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Too many rows");
        verifyNoInteractions(userRepo, centerRepo);
    }

    @Test
    void formulaCellsAreRejected() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("S");
            sh.createRow(0).createCell(0).setCellValue("h");
            Row r = sh.createRow(1);
            r.createCell(0).setCellFormula("1+1");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            bytes = out.toByteArray();
        }
        assertThatThrownBy(() -> service.importResidents(new MockMultipartFile("file", "r.xlsx", XLSX, bytes), platformAdmin()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("formulas");
    }

    @Test
    void fileTypeAndSizeAreValidated() throws Exception {
        byte[] ok = workbook(new String[10], rows(resident("res.one", "Passw0rd!", "CA")));
        assertThatThrownBy(() -> service.importResidents(new MockMultipartFile("file", "r.csv", XLSX, ok), platformAdmin()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.importResidents(new MockMultipartFile("file", "r.xlsx", "text/html", ok), platformAdmin()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.importResidents(new MockMultipartFile("file", "r.xlsx", XLSX, new byte[0]), platformAdmin()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.importResidents(new MockMultipartFile("file", "r.xlsx", XLSX, "not a zip".getBytes()), platformAdmin()))
            .isInstanceOf(IllegalArgumentException.class);
        ReflectionTestUtils.setField(service, "maxBytes", 10L);
        assertThatThrownBy(() -> service.importResidents(new MockMultipartFile("file", "r.xlsx", XLSX, ok), platformAdmin()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too large");
        verifyNoInteractions(userRepo);
    }

    @Test
    void fileWithoutDataRowsIsRejected() throws Exception {
        assertThatThrownBy(() -> service.importResidents(file(rows()), platformAdmin()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planLimitStopsTheWholeImportBeforeAnyWrite() throws Exception {
        doThrow(new PlanLimitExceededException("limit")).when(plan).checkUserLimit(eq(tA), anyInt());
        assertThatThrownBy(() -> service.importResidents(file(rows(resident("res.one", "Passw0rd!", "CA"))), platformAdmin()))
            .isInstanceOf(PlanLimitExceededException.class);
        verify(userRepo, never()).saveAllAndFlush(any());
    }

    private static <T> T eq(T v) { return org.mockito.ArgumentMatchers.eq(v); }
}
