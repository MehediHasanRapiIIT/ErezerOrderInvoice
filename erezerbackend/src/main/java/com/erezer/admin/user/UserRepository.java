package com.erezer.admin.user;

import com.erezer.admin.security.Role;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    /** Callers must pass an already-normalized email (see {@link User#normalizeEmail}). */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRole(Role role);

    long countByRoleAndActiveTrue(Role role);

    /** Bulk update: bypasses the version column on purpose (sign-ins are not edits). */
    @Modifying
    @Query("update User u set u.failedLoginAttempts = :attempts, u.lockedUntil = :lockedUntil where u.id = :id")
    void recordLoginFailure(@Param("id") long id, @Param("attempts") int attempts,
            @Param("lockedUntil") Instant lockedUntil);

    @Modifying
    @Query("update User u set u.failedLoginAttempts = 0, u.lockedUntil = null, u.lastLoginAt = :now where u.id = :id")
    void recordLoginSuccess(@Param("id") long id, @Param("now") Instant now);
}
