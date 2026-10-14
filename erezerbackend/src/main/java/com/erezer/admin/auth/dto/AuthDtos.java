package com.erezer.admin.auth.dto;

import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(max = 72) String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 10, max = 72) String newPassword) {
    }

    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt, CurrentUserResponse user) {

        public TokenResponse(String accessToken, Instant expiresAt, CurrentUserResponse user) {
            this(accessToken, "Bearer", expiresAt, user);
        }
    }

    public record CurrentUserResponse(
            long id,
            String fullName,
            String email,
            String phone,
            Role role,
            List<Permission> permissions) {
    }
}
