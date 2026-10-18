package com.erezer.admin.auth;

import com.erezer.admin.auth.RefreshTokenService.ClientInfo;
import com.erezer.admin.auth.dto.AuthDtos.CurrentUserResponse;
import com.erezer.admin.auth.dto.AuthDtos.TokenResponse;
import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.security.JwtTokenService;
import com.erezer.admin.security.JwtTokenService.IssuedToken;
import com.erezer.admin.security.PasswordPolicy;
import com.erezer.admin.security.SecurityProperties;
import com.erezer.admin.user.User;
import com.erezer.admin.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokens;
    private final SecurityProperties.Login loginProperties;
    private final Clock clock;
    /** Compared against when the email is unknown, so response time does not reveal which emails exist. */
    private final String dummyHash;

    AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtTokenService jwtTokenService,
            RefreshTokenService refreshTokens, SecurityProperties properties, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokens = refreshTokens;
        this.loginProperties = properties.login();
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("timing-equalizer-not-a-real-password-1");
    }

    /** Failed attempts must be persisted even though the method throws. */
    @Transactional(noRollbackFor = ApiException.class)
    Session login(String email, String password, ClientInfo client) {
        Instant now = clock.instant();
        User user = users.findByEmail(User.normalizeEmail(email)).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            throw invalidCredentials();
        }
        if (user.isLocked(now)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_LOCKED",
                    "Too many failed attempts. Try again in a few minutes.");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            int attempts = user.getFailedLoginAttempts() + 1;
            boolean lock = attempts >= loginProperties.maxFailedAttempts();
            users.recordLoginFailure(user.getId(), lock ? 0 : attempts, lock ? now.plus(loginProperties.lockoutDuration()) : null);
            log.info("Failed login for user {}{}", user.getId(), lock ? " (account locked)" : "");
            throw invalidCredentials();
        }
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED",
                    "This account has been disabled. Contact an administrator.");
        }
        users.recordLoginSuccess(user.getId(), now);
        return newSession(user, client);
    }

    @Transactional(noRollbackFor = ApiException.class)
    Session refresh(String rawRefreshToken, ClientInfo client) {
        User user = refreshTokens.consume(rawRefreshToken);
        return newSession(user, client);
    }

    @Transactional
    void logout(String rawRefreshToken) {
        refreshTokens.revoke(rawRefreshToken);
    }

    @Transactional(readOnly = true)
    CurrentUserResponse currentUser(long userId) {
        return toResponse(loadUser(userId));
    }

    /** Changes the password, ends every other session and returns a fresh session for this device. */
    @Transactional
    Session changePassword(long userId, String currentPassword, String newPassword, ClientInfo client) {
        User user = loadUser(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("WRONG_PASSWORD", "Current password is incorrect");
        }
        PasswordPolicy.validate(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("SAME_PASSWORD", "New password must differ from the current one");
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        user.invalidateSessions(clock.instant()); // other devices' access tokens stop working immediately
        refreshTokens.revokeAll(userId);
        return newSession(user, client);
    }

    private Session newSession(User user, ClientInfo client) {
        IssuedToken access = jwtTokenService.issueAccessToken(user);
        String refresh = refreshTokens.create(user, client);
        return new Session(new TokenResponse(access.value(), access.expiresAt(), toResponse(user)), refresh);
    }

    private User loadUser(long userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User", userId));
    }

    static CurrentUserResponse toResponse(User user) {
        return new CurrentUserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(),
                user.getRole(), user.effectivePermissions().stream().sorted().toList());
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect");
    }

    /** Body for the client plus the raw refresh token, which only ever leaves as an HttpOnly cookie. */
    record Session(TokenResponse body, String refreshToken) {
    }
}
