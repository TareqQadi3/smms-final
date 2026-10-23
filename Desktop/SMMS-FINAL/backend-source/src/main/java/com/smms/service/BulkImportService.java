package com.smms.service;

import com.smms.entity.*;
import com.smms.enums.UserRole;
import com.smms.repository.*;
import com.smms.security.AdminScopePolicy;
import com.smms.util.InputSanitizer;
import com.smms.util.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class BulkImportService {

    private final UserRepository userRepo;
    private final CenterRepository centerRepo;
    private final PasswordEncoder passwordEncoder;
    private final AdminScopePolicy policy;
    private final PlanEnforcementService planEnforcement;

    // ── Templates ─────────────────────────────────────────────

    public byte[] residentTemplate() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Residents");
            String[] headers = {
                "username*", "password*", "fullName*", "fullNameAr",
                "email", "phone", "complexName", "buildingNumber", "unitNumber",
                "centerCode*"
            };
            writeHeaderRow(wb, sh, headers);
            // sample row
            Row sample = sh.createRow(1);
            String[] vals = {"resident01","Pass@1234","Ahmed Ali","أحمد علي",
                "ahmed@example.com","+966501234567","مجمع الورود","B3","102","HC001"};
            for (int i = 0; i < vals.length; i++) sample.createCell(i).setCellValue(vals[i]);
            for (int i = 0; i < headers.length; i++) sh.autoSizeColumn(i);
            return toBytes(wb);
        }
    }

    public byte[] departmentTemplate() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Departments");
            String[] headers = {
                "username*", "password*", "departmentName*", "departmentSupervisor",
                "departmentEmail", "extensionNumber", "email", "phone", "centerCode*"
            };
            writeHeaderRow(wb, sh, headers);
            Row sample = sh.createRow(1);
            String[] vals = {"dept01","Pass@1234","Emergency Dept","Dr. Salem",
                "emergency@hospital.sa","1234","dept@example.com","+966501234567","HC001"};
            for (int i = 0; i < vals.length; i++) sample.createCell(i).setCellValue(vals[i]);
            for (int i = 0; i < headers.length; i++) sh.autoSizeColumn(i);
            return toBytes(wb);
        }
    }

    // ── Import ────────────────────────────────────────────────

    private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9._@-]+$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_REPORTED_ERRORS = 100;
    private static final int MAX_ERROR_LENGTH = 200;

    @Value("${smms.import.max-bytes:2097152}")
    private long maxBytes = 2L * 1024 * 1024;
    @Value("${smms.import.max-rows:1000}")
    private int maxRows = 1000;

    /** Raw cells of one data row (1-based sheet row number for messages). */
    private record RawRow(int number, String[] cells) {}

    /** Validated, not yet persisted row. */
    private record Pending(int number, User user, String rawPassword) {}

    /**
     * Imports residents. All-or-nothing: the whole file is parsed and validated first and nothing
     * is written unless every row is valid, so a bad row can never leave a half-imported batch.
     */
    @Transactional
    public ImportResult importResidents(MultipartFile file, User actor) throws Exception {
        return importUsers(file, actor, UserRole.resident, 10, 9, (c, center) -> User.builder()
            .username(c[0])
            .fullName(InputSanitizer.clean(c[2], 200))
            .fullNameAr(blankToNull(InputSanitizer.clean(c[3], 200)))
            .phone(blankToNull(c[5]))
            .complexName(blankToNull(InputSanitizer.clean(c[6], 200)))
            .buildingNumber(blankToNull(InputSanitizer.clean(c[7], 50)))
            .unitNumber(blankToNull(InputSanitizer.clean(c[8], 50)))
            .build(), 4, 5, "username, password, fullName are required", 2);
    }

    /** Imports department accounts; same all-or-nothing semantics as {@link #importResidents}. */
    @Transactional
    public ImportResult importDepartments(MultipartFile file, User actor) throws Exception {
        return importUsers(file, actor, UserRole.department, 9, 8, (c, center) -> User.builder()
            .username(c[0])
            .fullName(InputSanitizer.clean(c[2], 200))
            .phone(blankToNull(c[7]))
            .departmentName(InputSanitizer.clean(c[2], 200))
            .departmentSupervisor(blankToNull(InputSanitizer.clean(c[3], 200)))
            .departmentEmail(blankToNull(c[4]))
            .extensionNumber(blankToNull(InputSanitizer.clean(c[5], 20)))
            .build(), 6, 7, "username, password, departmentName are required", 2);
    }

    private interface Mapper { User map(String[] cells, Center center); }

    /**
     * @param columns        number of columns of the template
     * @param centerCol      column of the center code
     * @param emailCol       column of the account email (a placeholder is used when empty)
     * @param phoneCol       column of the phone number
     * @param requiredMsg    message for missing required cells (columns 0, 1 and nameCol)
     * @param nameCol        column of the required display name
     */
    private ImportResult importUsers(MultipartFile file, User actor, UserRole role, int columns, int centerCol,
                                     Mapper mapper, int emailCol, int phoneCol, String requiredMsg,
                                     int nameCol) throws Exception {
        validateFile(file);
        // Role rule first, once for the whole file (403)
        policy.assertCanAssignRole(actor, role);

        List<RawRow> rows = readRows(file, columns);
        if (rows.isEmpty()) throw new IllegalArgumentException("The file contains no data rows");

        UUID ctxTenant = TenantContext.get();
        List<String> errors = new ArrayList<>();
        int failedRows = 0;
        List<Pending> pending = new ArrayList<>();
        Set<String> usernames = new HashSet<>();
        Set<String> emails = new HashSet<>();
        Map<String, Center> centers = new HashMap<>();

        for (RawRow row : rows) {
            String[] c = row.cells();
            int before = errors.size();
            String where = "Row " + row.number() + ": ";

            if (!StringUtils.hasText(c[0]) || !StringUtils.hasText(c[1]) || !StringUtils.hasText(c[nameCol])) {
                errors.add(where + requiredMsg);
            }
            String username = c[0];
            if (StringUtils.hasText(username)) {
                if (username.length() < 3 || username.length() > 100 || !USERNAME.matcher(username).matches()) {
                    errors.add(where + "username must be 3-100 characters: letters, digits, dots, hyphens, underscores or @");
                } else if (!usernames.add(username)) {
                    errors.add(where + "username '" + username + "' appears more than once in the file");
                } else if (userRepo.existsByUsernameAndDeletedAtIsNull(username)) {
                    errors.add(where + "username '" + username + "' already exists");
                }
            }
            if (StringUtils.hasText(c[1]) && (c[1].length() < 8 || c[1].length() > 128)) {
                errors.add(where + "password must be 8-128 characters");
            }
            String email = c[emailCol];
            if (StringUtils.hasText(email) && (email.length() > 255 || !EMAIL.matcher(email).matches())) {
                errors.add(where + "email is not a valid address");
            }
            if (role == UserRole.department && StringUtils.hasText(c[4])
                    && (c[4].length() > 255 || !EMAIL.matcher(c[4]).matches())) {
                errors.add(where + "departmentEmail is not a valid address");
            }
            if (StringUtils.hasText(c[phoneCol]) && c[phoneCol].length() > 20) {
                errors.add(where + "phone must be at most 20 characters");
            }
            if (StringUtils.hasText(c[nameCol]) && c[nameCol].length() > 200) {
                errors.add(where + "name must be at most 200 characters");
            }

            Center center = null;
            if (!StringUtils.hasText(c[centerCol])) {
                errors.add(where + "centerCode is required");
            } else {
                center = resolveCenter(c[centerCol], ctxTenant, actor, centers);
                if (center == null) {
                    // Missing, other tenant and out of scope look the same
                    errors.add(where + "center code '" + abbreviate(c[centerCol]) + "' not found");
                } else if (center.getTenantId() == null) {
                    errors.add(where + "center has no tenant assigned");
                    center = null;
                }
            }

            String resolvedEmail = null;
            if (StringUtils.hasText(username) && errors.size() == before) {
                resolvedEmail = StringUtils.hasText(email) ? email : username + "@smms.local";
                if (!emails.add(resolvedEmail.toLowerCase(Locale.ROOT))) {
                    errors.add(where + "email '" + resolvedEmail + "' appears more than once in the file");
                } else if (userRepo.existsByEmailAndDeletedAtIsNull(resolvedEmail)) {
                    errors.add(where + "email '" + resolvedEmail + "' already exists");
                }
            }

            if (errors.size() > before) { failedRows++; continue; }

            User user = mapper.map(c, center);
            user.setEmail(resolvedEmail);
            user.setRole(role);
            user.setCenter(center);
            user.setTenantId(center.getTenantId());
            user.setActive(true);
            pending.add(new Pending(row.number(), user, c[1]));
        }

        if (failedRows > 0) {
            return new ImportResult(0, failedRows, capErrors(errors));
        }

        // Plan limits of every tenant the file writes into, before the first write
        Map<UUID, Integer> perTenant = new LinkedHashMap<>();
        for (Pending p : pending) perTenant.merge(p.user().getTenantId(), 1, Integer::sum);
        perTenant.forEach(planEnforcement::checkUserLimit);

        // Phase 2: hash passwords and write everything in this one transaction
        List<User> toSave = new ArrayList<>(pending.size());
        for (Pending p : pending) {
            p.user().setPasswordHash(passwordEncoder.encode(p.rawPassword()));
            toSave.add(p.user());
        }
        try {
            userRepo.saveAllAndFlush(toSave);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request took a username/email after validation: nothing is committed
            throw new IllegalArgumentException("Import failed: a username or email already exists. Nothing was imported.");
        }
        return new ImportResult(toSave.size(), 0, List.of());
    }

    /** Tenant-scoped lookup, then the actor's own scope. Null when the row must report "not found". */
    private Center resolveCenter(String code, UUID ctxTenant, User actor, Map<String, Center> cache) {
        String key = code.toUpperCase(Locale.ROOT);
        if (cache.containsKey(key)) return cache.get(key);
        Center found = (ctxTenant != null
            ? centerRepo.findByTenantIdAndCodeIgnoreCaseAndDeletedAtIsNull(ctxTenant, code)
            : centerRepo.findByCodeIgnoreCaseAndDeletedAtIsNull(code))
            .filter(center -> policy.centerInScope(actor, center))
            .orElse(null);
        cache.put(key, found);
        return found;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("File is empty");
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("Only .xlsx files are accepted");
        }
        String type = file.getContentType();
        if (type != null && !type.equalsIgnoreCase(XLSX_TYPE) && !type.equalsIgnoreCase("application/octet-stream")) {
            throw new IllegalArgumentException("Only .xlsx files are accepted");
        }
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("File is too large (max " + (maxBytes / 1024) + " KB)");
        }
    }

    /** Reads the first sheet. Formula cells are rejected (never evaluated); blank rows are skipped. */
    private List<RawRow> readRows(MultipartFile file, int columns) throws Exception {
        DataFormatter formatter = new DataFormatter();
        List<RawRow> rows = new ArrayList<>();
        try (InputStream in = file.getInputStream(); Workbook wb = new XSSFWorkbook(in)) {
            Sheet sh = wb.getSheetAt(0);
            for (int i = 1; i <= sh.getLastRowNum(); i++) {
                Row row = sh.getRow(i);
                if (row == null) continue;
                String[] cells = new String[columns];
                boolean blank = true;
                for (int col = 0; col < columns; col++) {
                    Cell cell = row.getCell(col);
                    if (cell == null) { cells[col] = ""; continue; }
                    if (cell.getCellType() == CellType.FORMULA) {
                        throw new IllegalArgumentException("Row " + (i + 1) + ": formulas are not allowed");
                    }
                    cells[col] = cellText(cell, formatter);
                    if (!cells[col].isEmpty()) blank = false;
                }
                if (blank) continue;
                if (rows.size() >= maxRows) {
                    throw new IllegalArgumentException("Too many rows (max " + maxRows + ")");
                }
                rows.add(new RawRow(i + 1, cells));
            }
        } catch (java.io.IOException | org.apache.poi.ooxml.POIXMLException e) {
            throw new IllegalArgumentException("The file is not a valid .xlsx workbook");
        }
        return rows;
    }

    /**
     * Numeric cells (phone numbers, codes) are written as plain digits: the General format of
     * DataFormatter would turn long numbers into scientific notation.
     */
    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell.getCellType() == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) {
            return java.math.BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        }
        return formatter.formatCellValue(cell).trim();
    }

    private static List<String> capErrors(List<String> errors) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(errors.size(), MAX_REPORTED_ERRORS); i++) {
            out.add(abbreviate(errors.get(i), MAX_ERROR_LENGTH));
        }
        if (errors.size() > MAX_REPORTED_ERRORS) {
            out.add("... and " + (errors.size() - MAX_REPORTED_ERRORS) + " more");
        }
        return out;
    }

    private static String abbreviate(String s) { return abbreviate(s, 50); }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String blankToNull(String s) { return StringUtils.hasText(s) ? s : null; }

    // ── Helpers ────────────────────────────────────────────────

    private void writeHeaderRow(XSSFWorkbook wb, Sheet sh, String[] headers) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        Row header = sh.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
        }
    }

    private byte[] toBytes(XSSFWorkbook wb) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }

    public record ImportResult(int success, int failed, List<String> errors) {}
}
