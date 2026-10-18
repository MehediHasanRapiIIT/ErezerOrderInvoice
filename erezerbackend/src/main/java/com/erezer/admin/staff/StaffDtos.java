package com.erezer.admin.staff;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class StaffDtos {

    private StaffDtos() {
    }

    private static final String PHONE = "^[0-9+\\-\\s]{6,30}$";

    /** @param password initial password; the staff member should change it after first sign-in */
    public record CreateStaffRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 160) String email,
            @Pattern(regexp = PHONE, message = "is not a valid phone number") String phone,
            @NotNull Role role,
            @NotBlank @Size(max = 72) String password,
            Set<Permission> permissions) {
    }

    public record UpdateStaffRequest(
            @NotBlank @Size(max = 120) String fullName,
            @Pattern(regexp = PHONE, message = "is not a valid phone number") String phone,
            @NotNull Role role,
            Set<Permission> permissions,
            @NotNull Long version) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(max = 72) String newPassword) {
    }

    public record StaffResponse(
            long id,
            String fullName,
            String email,
            String phone,
            Role role,
            boolean active,
            boolean locked,
            List<Permission> permissions,
            Instant lastLoginAt,
            Instant createdAt,
            long version) {
    }
}
