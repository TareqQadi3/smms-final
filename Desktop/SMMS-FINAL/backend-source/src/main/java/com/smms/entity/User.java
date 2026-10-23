package com.smms.entity;

import com.smms.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Entity @Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User extends BaseEntity implements UserDetails {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id")
    private Region region;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "center_id")
    private Center center;

    /** Link to a real department row (V13); the free-text department_* columns stay as display fallback. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "full_name_ar", length = 200)
    private String fullNameAr;

    @Column(length = 20)
    private String phone;

    // ── Resident-specific fields ───────────────────────────
    @Column(name = "complex_name", length = 200)
    private String complexName;          // مجمع

    @Column(name = "building_number", length = 50)
    private String buildingNumber;       // مبنى

    @Column(name = "unit_number", length = 50)
    private String unitNumber;           // وحدة

    // ── Department-specific fields ─────────────────────────
    @Column(name = "department_name", length = 200)
    private String departmentName;       // اسم القسم

    @Column(name = "department_supervisor", length = 200)
    private String departmentSupervisor; // مشرف القسم

    @Column(name = "department_email", length = 255)
    private String departmentEmail;      // إيميل القسم

    @Column(name = "extension_number", length = 20)
    private String extensionNumber;      // رقم التحويلة

    // ── General ───────────────────────────────────────────
    @Column(name = "job_title", length = 200)
    private String jobTitle;             // الوظيفة (للدور "أخرى")

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "user_role")
    private UserRole role;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "last_login")
    private Instant lastLogin;

    /** Soft flag: the client should send the user to the change-password screen. */
    @Column(name = "must_change_password", nullable = false)
    @Builder.Default
    private boolean mustChangePassword = false;

    /** Tokens issued before this instant are refused (deactivation, admin password reset). */
    @Column(name = "tokens_valid_after")
    private Instant tokensValidAfter;

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return isActive; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return isActive && getDeletedAt() == null; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { this.isActive = active; }
}
