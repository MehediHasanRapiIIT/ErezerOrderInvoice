package com.erezer.admin.staff;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.staff.StaffDtos.PasswordRequestView;
import com.erezer.admin.user.User;
import com.erezer.admin.user.UserRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Forgot password" without email: a staff member asks on the sign-in page, an admin sees the request on the Staff
 * page and sets a new password, then tells them. Asking answers the same way whether or not the email has an
 * account, so it reveals nothing.
 */
@Service
public class PasswordResetRequests {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetRequests.class);

    private final UserRepository users;
    private final JdbcClient jdbc;
    private final Clock clock;

    PasswordResetRequests(UserRepository users, JdbcClient jdbc, Clock clock) {
        this.users = users;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Records (or refreshes) the request when the email belongs to an active account; silent otherwise. */
    @Transactional
    public void request(String email, String ipAddress) {
        Optional<User> user = users.findByEmail(User.normalizeEmail(email)).filter(User::isActive);
        if (user.isEmpty()) {
            log.info("Password reset asked for an unknown or disabled email");
            return;
        }
        Timestamp now = Timestamp.from(clock.instant());
        String ip = ipAddress == null ? null : ipAddress.substring(0, Math.min(ipAddress.length(), 64));
        jdbc.sql("""
                        insert into password_reset_requests (user_id, requested_at, requested_ip) values (:user, :now, :ip)
                        on conflict (user_id) where resolved_at is null
                        do update set requested_at = excluded.requested_at, requested_ip = excluded.requested_ip
                        """)
                .param("user", user.get().getId()).param("now", now).param("ip", ip).update();
        log.info("Password reset asked for user {}", user.get().getId());
    }

    /** Open requests, newest first. */
    @Transactional(readOnly = true)
    public List<PasswordRequestView> open() {
        return jdbc.sql("""
                        select r.id, u.id, u.full_name, u.email, u.role, r.requested_at
                        from password_reset_requests r join users u on u.id = r.user_id
                        where r.resolved_at is null
                        order by r.requested_at desc
                        """)
                .query((rs, i) -> new PasswordRequestView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getTimestamp(6).toInstant()))
                .list();
    }

    /** Closes a request without changing the password (e.g. the person remembered it). */
    @Transactional
    public void dismiss(long requestId, long adminId) {
        int closed = jdbc.sql("update password_reset_requests set resolved_at = :now, resolved_by = :admin where id = :id and resolved_at is null")
                .param("now", Timestamp.from(clock.instant())).param("admin", adminId).param("id", requestId).update();
        if (closed == 0) {
            throw ApiException.notFound("Password request", requestId);
        }
    }

    /** Called when an admin sets a user's password: their open request is done. */
    @Transactional
    void resolveFor(long userId, long adminId, Instant now) {
        jdbc.sql("update password_reset_requests set resolved_at = :now, resolved_by = :admin where user_id = :user and resolved_at is null")
                .param("now", Timestamp.from(now)).param("admin", adminId).param("user", userId).update();
    }
}
