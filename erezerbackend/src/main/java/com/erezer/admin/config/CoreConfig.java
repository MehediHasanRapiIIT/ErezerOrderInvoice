package com.erezer.admin.config;

import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "currentAuditor")
@EnableScheduling
public class CoreConfig {

    /** Injected wherever "now" is needed so time-dependent logic stays testable. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Fills {@code @CreatedBy} columns with the id of the signed-in staff member. */
    @Bean
    AuditorAware<Long> currentAuditor() {
        return () -> Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(auth -> Long.valueOf(((JwtAuthenticationToken) auth).getToken().getSubject()));
    }
}
