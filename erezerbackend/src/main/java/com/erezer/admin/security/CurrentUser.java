package com.erezer.admin.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Access to the authenticated staff member of the current request.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** @throws IllegalStateException if called outside an authenticated request */
    public static long id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return Long.parseLong(jwt.getToken().getSubject());
        }
        throw new IllegalStateException("No authenticated user in the current context");
    }
}
