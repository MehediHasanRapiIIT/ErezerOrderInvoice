package com.erezer.admin.auth;

import com.erezer.admin.auth.RefreshToken.RevokeReason;
import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.security.SecurityProperties;
import com.erezer.admin.user.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque, rotating refresh tokens. Presenting an already-rotated token outside a short grace window is
 * treated as theft and revokes every session of that user.
 */
@Service
class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** Tolerates parallel refreshes from several browser tabs sharing one cookie. */
    private static final Duration REUSE_GRACE = Duration.ofSeconds(20);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository tokens;
    private final SecurityProperties.Jwt jwtProperties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    RefreshTokenService(RefreshTokenRepository tokens, SecurityProperties properties, Clock clock) {
        this.tokens = tokens;
        this.jwtProperties = properties.jwt();
        this.clock = clock;
    }

    /** Creates a new token and returns its raw value (only the hash is persisted). */
    @Transactional
    String create(User user, ClientInfo client) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        tokens.save(new RefreshToken(user, hash(raw), now, now.plus(jwtProperties.refreshTokenTtl()),
                client.userAgent(), client.ipAddress()));
        return raw;
    }

    /** Validates and revokes the presented token, returning its owner. The caller issues a replacement. */
    @Transactional(noRollbackFor = ApiException.class)
    User consume(String raw) {
        Instant now = clock.instant();
        RefreshToken token = tokens.findByTokenHash(hash(raw)).orElseThrow(RefreshTokenService::invalid);
        User user = token.getUser();

        if (token.isRevoked()) {
            if (token.getRevokeReason() != RevokeReason.ROTATED) {
                throw invalid();
            }
            boolean withinGrace = token.getRevokedAt().plus(REUSE_GRACE).isAfter(now);
            if (!withinGrace) {
                log.warn("Refresh token reuse detected for user {}; revoking all sessions", user.getId());
                tokens.revokeAllForUser(user.getId(), now);
                throw invalid();
            }
        }
        if (token.isExpired(now) || !user.isActive()) {
            throw invalid();
        }
        token.revoke(now, RevokeReason.ROTATED);
        return user;
    }

    @Transactional
    void revoke(String raw) {
        tokens.findByTokenHash(hash(raw)).ifPresent(t -> t.revoke(clock.instant(), RevokeReason.LOGOUT));
    }

    @Transactional
    void revokeAll(long userId) {
        tokens.revokeAllForUser(userId, clock.instant());
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "${app.time-zone}")
    @Transactional
    void purgeStale() {
        Instant now = clock.instant();
        int removed = tokens.deleteStale(now.minus(Duration.ofDays(1)), now.minus(Duration.ofDays(30)));
        if (removed > 0) {
            log.info("Purged {} stale refresh tokens", removed);
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Your session has expired. Please sign in again.");
    }

    private static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    record ClientInfo(String userAgent, String ipAddress) {

        ClientInfo {
            if (userAgent != null && userAgent.length() > 255) {
                userAgent = userAgent.substring(0, 255);
            }
            if (ipAddress != null && ipAddress.length() > 45) {
                ipAddress = ipAddress.substring(0, 45);
            }
        }
    }
}
