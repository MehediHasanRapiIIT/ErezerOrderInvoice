package com.erezer.admin.user;

import com.erezer.admin.security.PasswordPolicy;
import com.erezer.admin.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first admin from {@code BOOTSTRAP_ADMIN_*} so a fresh installation can be logged into.
 * Does nothing once any admin exists, so changing the variables later has no effect.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapAdminProperties properties;

    AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder, BootstrapAdminProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByRole(Role.ADMIN)) {
            return;
        }
        if (!properties.isConfigured()) {
            log.warn("No admin account exists and BOOTSTRAP_ADMIN_EMAIL/BOOTSTRAP_ADMIN_PASSWORD are not set; "
                    + "nobody will be able to log in.");
            return;
        }
        PasswordPolicy.validate(properties.password());
        String name = properties.fullName() == null || properties.fullName().isBlank()
                ? "Administrator" : properties.fullName();
        users.save(new User(name, properties.email(), null, passwordEncoder.encode(properties.password()), Role.ADMIN));
        log.info("Created bootstrap admin account {}", User.normalizeEmail(properties.email()));
    }
}
