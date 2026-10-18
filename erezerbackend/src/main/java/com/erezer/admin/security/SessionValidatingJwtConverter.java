package com.erezer.admin.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.core.convert.converter.Converter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

/**
 * Turns a verified JWT into an authentication, but only while the account is still active and the token was
 * issued after the account's {@code sessions_valid_after} mark. Deactivating a user, resetting their password
 * or changing their role/permissions moves that mark, so old access tokens stop working on the next request
 * instead of living out their 15 minutes. (The SPA then refreshes and receives a token with the new permissions,
 * unless the account was deactivated.)
 *
 * <p>Costs one primary-key lookup per API request, which is negligible for an admin panel.
 */
@Component
class SessionValidatingJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JdbcClient jdbc;
    private final JwtAuthenticationConverter delegate;

    SessionValidatingJwtConverter(JdbcClient jdbc) {
        this.jdbc = jdbc;
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtTokenService.CLAIM_AUTHORITIES);
        authorities.setAuthorityPrefix("");
        this.delegate = new JwtAuthenticationConverter();
        this.delegate.setJwtGrantedAuthoritiesConverter(authorities);
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException ex) {
            throw new InvalidBearerTokenException("Malformed subject");
        }
        Optional<SessionState> state = jdbc.sql("select active, sessions_valid_after from users where id = :id")
                .param("id", userId)
                .query((rs, i) -> new SessionState(rs.getBoolean("active"),
                        Optional.ofNullable(rs.getTimestamp("sessions_valid_after")).map(Timestamp::toInstant).orElse(null)))
                .optional();
        if (state.isEmpty() || !state.get().active()) {
            throw new InvalidBearerTokenException("Account is not active");
        }
        Instant validAfter = state.get().validAfter();
        Instant issuedAt = jwt.getIssuedAt();
        // JWT "iat" has whole-second precision, so compare at that precision.
        if (validAfter != null && (issuedAt == null || issuedAt.isBefore(validAfter.truncatedTo(ChronoUnit.SECONDS)))) {
            throw new InvalidBearerTokenException("Session was revoked");
        }
        return delegate.convert(jwt);
    }

    private record SessionState(boolean active, Instant validAfter) {
    }
}
