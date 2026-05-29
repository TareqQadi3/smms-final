package com.smms.service;

import com.smms.entity.*;
import com.smms.enums.UserRole;
import com.smms.repository.*;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class BulkImportService {

    private final UserRepository userRepo;
    private final CenterRepository centerRepo;
    private final PasswordEncoder passwordEncoder;

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

    @Transactional
    public ImportResult importResidents(MultipartFile file) throws Exception {
        int success = 0;
        List<String> errors = new ArrayList<>();

        try (InputStream in = file.getInputStream();
             Workbook wb = new XSSFWorkbook(in)) {
            Sheet sh = wb.getSheetAt(0);
            for (int i = 1; i <= sh.getLastRowNum(); i++) {
                Row row = sh.getRow(i);
                if (row == null || isBlankRow(row)) continue;
                try {
                    String username    = cell(row, 0);
                    String password    = cell(row, 1);
                    String fullName    = cell(row, 2);
                    String fullNameAr  = cell(row, 3);
                    String email       = cell(row, 4);
                    String phone       = cell(row, 5);
                    String complexName = cell(row, 6);
                    String building    = cell(row, 7);
                    String unit        = cell(row, 8);
                    String centerCode  = cell(row, 9);

                    if (!StringUtils.hasText(username) || !StringUtils.hasText(password)
                            || !StringUtils.hasText(fullName)) {
                        errors.add("Row " + (i + 1) + ": username, password, fullName are required");
                        continue;
                    }
                    if (userRepo.existsByUsernameAndDeletedAtIsNull(username)) {
                        errors.add("Row " + (i + 1) + ": username '" + username + "' already exists");
                        continue;
                    }

                    Center center = null;
                    if (StringUtils.hasText(centerCode)) {
                        center = centerRepo.findByCodeAndDeletedAtIsNull(centerCode).orElse(null);
                        if (center == null) {
                            errors.add("Row " + (i + 1) + ": center code '" + centerCode + "' not found");
                            continue;
                        }
                    } else {
                        errors.add("Row " + (i + 1) + ": centerCode is required");
                        continue;
                    }

                    String resolvedEmail = StringUtils.hasText(email) ? email : username + "@smms.local";
                    User user = User.builder()
                        .username(username)
                        .email(resolvedEmail)
                        .passwordHash(passwordEncoder.encode(password))
                        .fullName(fullName)
                        .fullNameAr(StringUtils.hasText(fullNameAr) ? fullNameAr : null)
                        .phone(StringUtils.hasText(phone) ? phone : null)
                        .complexName(StringUtils.hasText(complexName) ? complexName : null)
                        .buildingNumber(StringUtils.hasText(building) ? building : null)
                        .unitNumber(StringUtils.hasText(unit) ? unit : null)
                        .role(UserRole.resident)
                        .center(center)
                        .isActive(true)
                        .build();
                    userRepo.save(user);
                    success++;
                } catch (Exception e) {
                    errors.add("Row " + (i + 1) + ": " + e.getMessage());
                }
            }
        }
        return new ImportResult(success, errors);
    }

    @Transactional
    public ImportResult importDepartments(MultipartFile file) throws Exception {
        int success = 0;
        List<String> errors = new ArrayList<>();

        try (InputStream in = file.getInputStream();
             Workbook wb = new XSSFWorkbook(in)) {
            Sheet sh = wb.getSheetAt(0);
            for (int i = 1; i <= sh.getLastRowNum(); i++) {
                Row row = sh.getRow(i);
                if (row == null || isBlankRow(row)) continue;
                try {
                    String username    = cell(row, 0);
                    String password    = cell(row, 1);
                    String deptName    = cell(row, 2);
                    String deptSuper   = cell(row, 3);
                    String deptEmail   = cell(row, 4);
                    String extNum      = cell(row, 5);
                    String email       = cell(row, 6);
                    String phone       = cell(row, 7);
                    String centerCode  = cell(row, 8);

                    if (!StringUtils.hasText(username) || !StringUtils.hasText(password)
                            || !StringUtils.hasText(deptName)) {
                        errors.add("Row " + (i + 1) + ": username, password, departmentName are required");
                        continue;
                    }
                    if (userRepo.existsByUsernameAndDeletedAtIsNull(username)) {
                        errors.add("Row " + (i + 1) + ": username '" + username + "' already exists");
                        continue;
                    }

                    Center center = null;
                    if (StringUtils.hasText(centerCode)) {
                        center = centerRepo.findByCodeAndDeletedAtIsNull(centerCode).orElse(null);
                        if (center == null) {
                            errors.add("Row " + (i + 1) + ": center code '" + centerCode + "' not found");
                            continue;
                        }
                    } else {
                        errors.add("Row " + (i + 1) + ": centerCode is required");
                        continue;
                    }

                    String resolvedEmail = StringUtils.hasText(email) ? email : username + "@smms.local";
                    User user = User.builder()
                        .username(username)
                        .email(resolvedEmail)
                        .passwordHash(passwordEncoder.encode(password))
                        .fullName(StringUtils.hasText(deptName) ? deptName : username)
                        .phone(StringUtils.hasText(phone) ? phone : null)
                        .departmentName(deptName)
                        .departmentSupervisor(StringUtils.hasText(deptSuper) ? deptSuper : null)
                        .departmentEmail(StringUtils.hasText(deptEmail) ? deptEmail : null)
                        .extensionNumber(StringUtils.hasText(extNum) ? extNum : null)
                        .role(UserRole.department)
                        .center(center)
                        .isActive(true)
                        .build();
                    userRepo.save(user);
                    success++;
                } catch (Exception e) {
                    errors.add("Row " + (i + 1) + ": " + e.getMessage());
                }
            }
        }
        return new ImportResult(success, errors);
    }

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

    private String cell(Row row, int col) {
        Cell c = row.getCell(col);
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING  -> c.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) c.getNumericCellValue());
            default      -> "";
        };
    }

    private boolean isBlankRow(Row row) {
        for (int i = 0; i < row.getLastCellNum(); i++) {
            Cell c = row.getCell(i);
            if (c != null && c.getCellType() != CellType.BLANK
                    && StringUtils.hasText(c.toString())) return false;
        }
        return true;
    }

    private byte[] toBytes(XSSFWorkbook wb) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }

    public record ImportResult(int success, List<String> errors) {}
}
