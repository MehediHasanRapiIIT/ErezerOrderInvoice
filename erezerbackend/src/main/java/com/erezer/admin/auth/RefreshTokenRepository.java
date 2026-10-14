package com.erezer.admin.auth;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    @Query("select t from RefreshToken t join fetch t.user where t.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHash(@Param("hash") String tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now, t.revokeReason = com.erezer.admin.auth.RefreshToken.RevokeReason.REVOKED where t.user.id = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") long userId, @Param("now") Instant now);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :expiredBefore or t.revokedAt < :revokedBefore")
    int deleteStale(@Param("expiredBefore") Instant expiredBefore, @Param("revokedBefore") Instant revokedBefore);
}
