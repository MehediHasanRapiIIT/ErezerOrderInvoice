package com.erezer.admin.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull RefreshCookie refreshCookie,
        @Valid @NotNull Login login,
        @Valid @NotNull Cors cors) {

    /**
     * @param secret base64-encoded HMAC-SHA256 key (at least 32 bytes once decoded)
     */
    public record Jwt(
            @NotBlank(message = "JWT_SECRET must be set") String secret,
            @NotBlank String issuer,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl) {
    }

    public record RefreshCookie(@NotBlank String name, @NotBlank String path, boolean secure) {
    }

    public record Login(@Min(1) int maxFailedAttempts, @NotNull Duration lockoutDuration) {
    }

    public record Cors(List<String> allowedOrigins) {

        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of()
                    : allowedOrigins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }
}
