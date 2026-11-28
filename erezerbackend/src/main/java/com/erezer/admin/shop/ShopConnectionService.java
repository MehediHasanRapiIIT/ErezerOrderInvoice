package com.erezer.admin.shop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The secret key the Erezer shop sends with its orders. An admin creates it in "Shop connection" and pastes it into
 * the shop's settings; it is shown once and only its SHA-256 is stored. Making a new key replaces the old one.
 *
 * <p>Orders from the shop are recorded as created by a sign-in-less staff account, "Erezer website": it is inactive,
 * so it never appears in staff lists and can't log in.
 */
@Service
public class ShopConnectionService {

    static final String SYSTEM_EMAIL = "shop-orders@erezer.invalid";
    static final String SYSTEM_NAME = "Erezer website";
    private static final String PREFIX = "ezs_";

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    ShopConnectionService(JdbcClient jdbc, PasswordEncoder passwordEncoder, Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** Whether a key is set, and which one (its last characters) since when. */
    public record Status(boolean connected, String keyHint, Instant createdAt) {
    }

    public Status status() {
        return jdbc.sql("select key_hint, created_at from shop_connection where id = 1")
                .query((rs, i) -> new Status(true, rs.getString(1), rs.getTimestamp(2).toInstant()))
                .optional()
                .orElse(new Status(false, null, null));
    }

    /** A new key, replacing any old one at once. Returned only here: it can't be shown again. */
    @Transactional
    public String newKey(long createdBy) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String key = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("""
                        insert into shop_connection (id, key_hash, key_hint, created_at, created_by)
                        values (1, :hash, :hint, :now, :by)
                        on conflict (id) do update set key_hash = excluded.key_hash, key_hint = excluded.key_hint,
                            created_at = excluded.created_at, created_by = excluded.created_by""")
                .param("hash", sha256(key))
                .param("hint", "…" + key.substring(key.length() - 4))
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .param("by", createdBy)
                .update();
        return key;
    }

    /** Disconnects the shop: its key stops working at once. */
    @Transactional
    public void removeKey() {
        jdbc.sql("delete from shop_connection where id = 1").update();
    }

    /** True when {@code key} is the current key (compared in constant time). */
    public boolean accepts(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String stored = jdbc.sql("select key_hash from shop_connection where id = 1").query(String.class).optional().orElse(null);
        return stored != null && MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII),
                sha256(key.trim()).getBytes(StandardCharsets.US_ASCII));
    }

    /** The "Erezer website" account that shop orders are recorded under, made the first time it's needed. */
    @Transactional
    public long systemUserId() {
        return jdbc.sql("select id from users where lower(email) = :email").param("email", SYSTEM_EMAIL)
                .query(Long.class).optional()
                .orElseGet(() -> jdbc.sql("""
                                insert into users (full_name, email, password_hash, role, active, created_at, updated_at)
                                values (:name, :email, :hash, 'MODERATOR', false, :now, :now) returning id""")
                        .param("name", SYSTEM_NAME)
                        .param("email", SYSTEM_EMAIL)
                        // A random password nobody knows; the account is inactive anyway.
                        .param("hash", passwordEncoder.encode(UUID.randomUUID().toString() + UUID.randomUUID()))
                        .param("now", java.sql.Timestamp.from(clock.instant()))
                        .query(Long.class).single());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
