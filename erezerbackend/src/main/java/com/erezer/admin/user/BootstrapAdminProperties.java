package com.erezer.admin.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * First administrator account, created on startup only when no admin exists yet.
 */
@ConfigurationProperties(prefix = "app.bootstrap.admin")
public record BootstrapAdminProperties(String fullName, String email, String password) {

    public boolean isConfigured() {
        return email != null && !email.isBlank() && password != null && !password.isBlank();
    }
}
