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

    /** Whether the current request comes from an admin (the role is a claim of the access token). */
    public static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken jwt
                && Role.ADMIN.name().equals(jwt.getToken().getClaimAsString(JwtTokenService.CLAIM_ROLE));
    }

    /** Whether the current request's user holds a permission (false outside a request). */
    public static boolean has(Permission permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> permission.name().equals(a.getAuthority()));
    }
}
