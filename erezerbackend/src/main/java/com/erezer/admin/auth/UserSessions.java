package com.erezer.admin.auth;

import org.springframework.stereotype.Service;

/** Public entry point for other modules (e.g. staff management) to sign a user out everywhere. */
@Service
public class UserSessions {

    private final RefreshTokenService refreshTokens;

    UserSessions(RefreshTokenService refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    /** Revokes every refresh token of the user; pair with {@code User#invalidateSessions} for access tokens. */
    public void revokeAll(long userId) {
        refreshTokens.revokeAll(userId);
    }
}
