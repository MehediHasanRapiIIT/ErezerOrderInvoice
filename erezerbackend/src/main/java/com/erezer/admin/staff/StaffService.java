package com.erezer.admin.staff;

import com.erezer.admin.auth.UserSessions;
import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.security.Permission;
import com.erezer.admin.security.PasswordPolicy;
import com.erezer.admin.security.Role;
import com.erezer.admin.staff.StaffDtos.CreateStaffRequest;
import com.erezer.admin.staff.StaffDtos.StaffResponse;
import com.erezer.admin.staff.StaffDtos.UpdateStaffRequest;
import com.erezer.admin.user.User;
import com.erezer.admin.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff accounts. Safety rules: you cannot deactivate or demote yourself, and the last active admin can never
 * be deactivated or demoted, so the panel can't be locked out.
 */
@Service
public class StaffService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetRequests passwordRequests;
    private final UserSessions sessions;
    private final Clock clock;

    StaffService(UserRepository users, PasswordEncoder passwordEncoder, UserSessions sessions,
            PasswordResetRequests passwordRequests, Clock clock) {
        this.passwordRequests = passwordRequests;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<StaffResponse> search(String query, Role role, Boolean active, Pageable pageable) {
        Specification<User> spec = Specification.allOf(Stream.of(search(query), roleIs(role), activeIs(active))
                .filter(Objects::nonNull).toList());
        Instant now = clock.instant();
        return PageResponse.of(users.findAll(spec, pageable), u -> toResponse(u, now));
    }

    @Transactional(readOnly = true)
    public StaffResponse get(long id) {
        return toResponse(load(id), clock.instant());
    }

    @Transactional
    public StaffResponse create(CreateStaffRequest request, long actingUserId) {
        User actor = load(actingUserId);
        if (!isAdmin(actor)) {
            if (request.role() == Role.ADMIN) {
                throw forbidden("Only an admin can add admins");
            }
            requireCanGive(actor, permissionsFor(request.role(), request.permissions()), EnumSet.noneOf(Permission.class));
        }
        String email = User.normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "A staff member with this email already exists");
        }
        PasswordPolicy.validate(request.password());
        User user = new User(request.fullName().trim(), email, blankToNull(request.phone()),
                passwordEncoder.encode(request.password()), request.role());
        user.replacePermissions(permissionsFor(request.role(), request.permissions()));
        return toResponse(users.save(user), clock.instant());
    }

    @Transactional
    public StaffResponse update(long id, UpdateStaffRequest request, long actingUserId) {
        User user = load(id);
        if (request.version() != user.getVersion()) {
            throw ApiException.conflict("STALE_DATA", "This account was changed by someone else. Reload and try again.");
        }
        User actor = load(actingUserId);
        if (!isAdmin(actor)) {
            requireNotAdminOrSelf(actor, user);
            requireHoldsAllOf(actor, user);
            if (request.role() == Role.ADMIN) {
                throw forbidden("Only an admin can make someone an admin");
            }
            requireCanGive(actor, permissionsFor(request.role(), request.permissions()), user.getPermissions());
        }
        boolean demoting = user.getRole() == Role.ADMIN && request.role() != Role.ADMIN;
        if (demoting && id == actingUserId) {
            throw ApiException.conflict("CANNOT_DEMOTE_SELF", "You cannot remove your own admin role");
        }
        if (demoting && user.isActive()) {
            requireAnotherActiveAdmin();
        }

        Set<Permission> newPermissions = permissionsFor(request.role(), request.permissions());
        boolean accessChanged = user.getRole() != request.role() || !user.getPermissions().equals(newPermissions);

        user.updateProfile(request.fullName().trim(), blankToNull(request.phone()));
        user.changeRole(request.role());
        user.replacePermissions(newPermissions);
        if (accessChanged) {
            // Old tokens carry the old permissions: reject them so the app fetches fresh ones right away.
            user.invalidateSessions(clock.instant());
        }
        return toResponse(user, clock.instant());
    }

    @Transactional
    public StaffResponse setActive(long id, boolean active, long actingUserId) {
        User user = load(id);
        requireMayManage(actingUserId, user);
        if (!active) {
            if (id == actingUserId) {
                throw ApiException.conflict("CANNOT_DEACTIVATE_SELF", "You cannot deactivate your own account");
            }
            if (user.getRole() == Role.ADMIN && user.isActive()) {
                requireAnotherActiveAdmin();
            }
            user.setActive(false);
            user.invalidateSessions(clock.instant());
            sessions.revokeAll(id);
        } else {
            user.setActive(true);
        }
        return toResponse(user, clock.instant());
    }

    /** Sets a new password chosen by an admin, unlocks the account and signs the user out everywhere. */
    @Transactional
    public StaffResponse resetPassword(long id, String newPassword, long adminId) {
        User user = load(id);
        requireMayManage(adminId, user);
        PasswordPolicy.validate(newPassword);
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        user.unlock();
        user.invalidateSessions(clock.instant());
        sessions.revokeAll(id);
        passwordRequests.resolveFor(id, adminId, clock.instant());
        return toResponse(user, clock.instant());
    }

    @Transactional
    public StaffResponse unlock(long id, long actingUserId) {
        User user = load(id);
        requireMayManage(actingUserId, user);
        user.unlock();
        return toResponse(user, clock.instant());
    }

    // ---- Staff managers who aren't admins ----
    // They may look after moderators, but never admins or their own account, and may only give permissions they
    // hold themselves; otherwise "Manage staff" would be a way to get every permission.

    private static boolean isAdmin(User user) {
        return user.getRole() == Role.ADMIN;
    }

    private void requireMayManage(long actingUserId, User target) {
        User actor = load(actingUserId);
        if (!isAdmin(actor)) {
            requireNotAdminOrSelf(actor, target);
            requireHoldsAllOf(actor, target);
        }
    }

    /**
     * A staff manager may only look after accounts whose permissions they hold themselves; otherwise resetting a
     * stronger account's password and signing in as it would give them its permissions.
     */
    private static void requireHoldsAllOf(User actor, User target) {
        Set<Permission> held = actor.effectivePermissions();
        List<String> beyond = target.effectivePermissions().stream()
                .filter(p -> !held.contains(p)).map(Permission::label).toList();
        if (!beyond.isEmpty()) {
            throw forbidden("Only someone who has all of this person's permissions can change their account. They also have: "
                    + String.join(", ", beyond));
        }
    }

    private static void requireNotAdminOrSelf(User actor, User target) {
        if (isAdmin(target)) {
            throw forbidden("Only an admin can change an admin's account");
        }
        if (target.getId().equals(actor.getId())) {
            throw forbidden("Ask an admin to change your own access");
        }
    }

    /** Newly given permissions (beyond what the account already had) must be ones the giver holds. */
    private static void requireCanGive(User actor, Set<Permission> requested, Set<Permission> already) {
        Set<Permission> held = actor.effectivePermissions();
        List<String> missing = requested.stream()
                .filter(p -> !already.contains(p) && !held.contains(p))
                .map(Permission::label).toList();
        if (!missing.isEmpty()) {
            throw forbidden("You can only give permissions you have yourself. Not yours: " + String.join(", ", missing));
        }
    }

    private static ApiException forbidden(String message) {
        return new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "NOT_ALLOWED", message);
    }

    private void requireAnotherActiveAdmin() {
        if (users.countByRoleAndActiveTrue(Role.ADMIN) <= 1) {
            throw ApiException.conflict("LAST_ADMIN", "There must always be at least one active admin");
        }
    }

    /** Admins implicitly hold everything, so nothing is stored for them; admin-only permissions are never stored. */
    private static Set<Permission> permissionsFor(Role role, Set<Permission> requested) {
        if (role == Role.ADMIN || requested == null) {
            return EnumSet.noneOf(Permission.class);
        }
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        requested.stream().filter(Objects::nonNull).filter(p -> !p.adminOnly()).forEach(result::add);
        return result;
    }

    private User load(long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("Staff member", id));
    }

    private static StaffResponse toResponse(User u, Instant now) {
        return new StaffResponse(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole(), u.isActive(),
                u.isLocked(now), u.effectivePermissions().stream().sorted().toList(), u.getLastLoginAt(),
                u.getCreatedAt(), u.getVersion());
    }

    private static Specification<User> search(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String pattern = "%" + query.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return (root, q, cb) -> cb.or(
                cb.like(cb.lower(root.get("fullName")), pattern, '\\'),
                cb.like(cb.lower(root.get("email")), pattern, '\\'),
                cb.like(cb.lower(root.get("phone")), pattern, '\\'));
    }

    private static Specification<User> roleIs(Role role) {
        return role == null ? null : (root, q, cb) -> cb.equal(root.get("role"), role);
    }

    private static Specification<User> activeIs(Boolean active) {
        return active == null ? null : (root, q, cb) -> cb.equal(root.get("active"), active);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
