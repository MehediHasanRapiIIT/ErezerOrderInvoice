package com.erezer.admin.auth;

import com.erezer.admin.auth.AuthService.Session;
import com.erezer.admin.auth.RefreshTokenService.ClientInfo;
import com.erezer.admin.auth.dto.AuthDtos.ChangePasswordRequest;
import com.erezer.admin.auth.dto.AuthDtos.CurrentUserResponse;
import com.erezer.admin.auth.dto.AuthDtos.LoginRequest;
import com.erezer.admin.auth.dto.AuthDtos.TokenResponse;
import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.security.CurrentUser;
import com.erezer.admin.security.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthService authService;
    private final SecurityProperties properties;

    AuthController(AuthService authService, SecurityProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return withRefreshCookie(authService.login(request.email(), request.password(), clientInfo(http)));
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(@CookieValue(name = "${app.security.refresh-cookie.name}", required = false)
            String refreshToken, HttpServletRequest http) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "NO_SESSION", "Not signed in");
        }
        return withRefreshCookie(authService.refresh(refreshToken, clientInfo(http)));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = "${app.security.refresh-cookie.name}", required = false)
            String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            authService.logout(refreshToken);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO)).build();
    }

    @GetMapping("/me")
    CurrentUserResponse me() {
        return authService.currentUser(CurrentUser.id());
    }

    @PutMapping("/password")
    ResponseEntity<TokenResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest http) {
        return withRefreshCookie(authService.changePassword(CurrentUser.id(), request.currentPassword(),
                request.newPassword(), clientInfo(http)));
    }

    private ResponseEntity<TokenResponse> withRefreshCookie(Session session) {
        String cookie = refreshCookie(session.refreshToken(), properties.jwt().refreshTokenTtl());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie).body(session.body());
    }

    private String refreshCookie(String value, Duration maxAge) {
        SecurityProperties.RefreshCookie cookie = properties.refreshCookie();
        return ResponseCookie.from(cookie.name(), value)
                .httpOnly(true)
                .secure(cookie.secure())
                .sameSite("Strict")
                .path(cookie.path())
                .maxAge(maxAge)
                .build()
                .toString();
    }

    private static ClientInfo clientInfo(HttpServletRequest request) {
        return new ClientInfo(request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr());
    }
}
