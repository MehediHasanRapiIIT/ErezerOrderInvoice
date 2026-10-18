package com.erezer.admin.user;

import com.erezer.admin.common.persistence.AuditableEntity;
import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Staff account. Sign-in bookkeeping (failed attempts, lockout, last login) is written with bulk updates in
 * {@link UserRepository}, so signing in never bumps the version and never makes an admin's open edit form stale.
 */
@Entity
@Table(name = "users")
public class User extends AuditableEntity {

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(name = "email", nullable = false, length = 160)
    private String email;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** Access tokens issued before this moment are rejected (see SessionValidatingJwtConverter). */
    @Column(name = "sessions_valid_after")
    private Instant sessionsValidAfter;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_permissions", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 40)
    private Set<Permission> permissions = new HashSet<>();

    protected User() {
        // for JPA
    }

    public User(String fullName, String email, String phone, String passwordHash, Role role) {
        this.fullName = fullName;
        this.email = normalizeEmail(email);
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Permissions actually in force: admins implicitly hold all of them. */
    public Set<Permission> effectivePermissions() {
        if (role == Role.ADMIN) {
            return EnumSet.allOf(Permission.class);
        }
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        permissions.stream().filter(p -> !p.adminOnly()).forEach(result::add);
        return result;
    }

    public void replacePermissions(Collection<Permission> newPermissions) {
        permissions.clear();
        newPermissions.stream().filter(p -> !p.adminOnly()).forEach(permissions::add);
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void changePasswordHash(String newHash) {
        this.passwordHash = newHash;
    }

    public void updateProfile(String fullName, String phone) {
        this.fullName = fullName;
        this.phone = phone;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    /** Makes every access token issued so far invalid on its next use. */
    public void invalidateSessions(Instant now) {
        this.sessionsValidAfter = now;
    }

    public void unlock() {
        this.lockedUntil = null;
        this.failedLoginAttempts = 0;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Set<Permission> getPermissions() {
        return Collections.unmodifiableSet(permissions);
    }
}
