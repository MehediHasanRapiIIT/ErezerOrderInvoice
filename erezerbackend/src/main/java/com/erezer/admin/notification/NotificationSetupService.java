package com.erezer.admin.notification;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.courier.SecretCipher;
import com.erezer.admin.notification.NotificationDtos.ActionView;
import com.erezer.admin.notification.NotificationDtos.PersonView;
import com.erezer.admin.notification.NotificationDtos.PushConfig;
import com.erezer.admin.notification.NotificationDtos.Rule;
import com.erezer.admin.notification.NotificationDtos.RuleView;
import com.erezer.admin.notification.NotificationDtos.SettingsRequest;
import com.erezer.admin.notification.NotificationDtos.SettingsView;
import com.erezer.admin.notification.NotificationDtos.Subscription;
import com.erezer.admin.security.Permission;
import com.erezer.admin.security.Role;
import com.erezer.admin.user.User;
import com.erezer.admin.user.UserRepository;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Everything the admin sets in the Notifications menu: how each action behaves, what each person gets, and the Firebase
 * connection. The connection lives only in the database, with the private key encrypted, so it is never in the repository.
 */
@Service
public class NotificationSetupService {

    record RuleState(boolean enabled, boolean push, boolean fromModerator, boolean fromEmployee, boolean fromAdmin) {

        boolean allows(String role) {
            return switch (role) {
                case "ADMIN" -> fromAdmin;
                case "EMPLOYEE" -> fromEmployee;
                default -> fromModerator;
            };
        }
    }

    record Recipient(long userId, boolean push) {
    }

    private record Saved(boolean see, boolean push) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern CONFIG_ENTRY = Pattern.compile(
            "[\"']?(apiKey|authDomain|projectId|storageBucket|messagingSenderId|appId|measurementId)[\"']?\\s*:\\s*[\"']([^\"']+)[\"']");

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final SecretCipher cipher;
    private final Clock clock;

    NotificationSetupService(JdbcClient jdbc, UserRepository users, SecretCipher cipher, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.cipher = cipher;
        this.clock = clock;
    }

    public List<ActionView> catalog() {
        return Arrays.stream(NotificationAction.values()).map(a -> new ActionView(a.name(), a.section(), a.label())).toList();
    }

    // ---- Rules ----

    RuleState ruleOf(NotificationAction action) {
        return rules().getOrDefault(action.name(), defaults(action));
    }

    private static RuleState defaults(NotificationAction action) {
        return new RuleState(action.enabledByDefault(), action.pushByDefault(), true, true, false);
    }

    private Map<String, RuleState> rules() {
        Map<String, RuleState> rules = new HashMap<>();
        jdbc.sql("select action, enabled, push, from_moderator, from_employee, from_admin from notification_rules")
                .query((rs, i) -> rules.put(rs.getString(1), new RuleState(rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6))))
                .list();
        return rules;
    }

    @Transactional(readOnly = true)
    public List<RuleView> ruleViews() {
        Map<String, RuleState> saved = rules();
        Map<String, Long> counts = new HashMap<>();
        for (NotificationAction action : NotificationAction.values()) {
            counts.put(action.name(), (long) recipientsOf(action, 0).size());
        }
        return Arrays.stream(NotificationAction.values()).map(a -> {
            RuleState rule = saved.getOrDefault(a.name(), defaults(a));
            return new RuleView(a.name(), a.section(), a.label(), rule.enabled(), rule.push(), rule.fromModerator(), rule.fromEmployee(),
                    rule.fromAdmin(), counts.get(a.name()));
        }).toList();
    }

    @Transactional
    public void saveRules(List<Rule> rules) {
        for (Rule rule : rules) {
            requireAction(rule.action());
            jdbc.sql("""
                            insert into notification_rules (action, enabled, push, from_moderator, from_employee, from_admin, updated_at)
                            values (:action, :enabled, :push, :moderator, :employee, :admin, :now)
                            on conflict (action) do update set enabled = excluded.enabled, push = excluded.push,
                                from_moderator = excluded.from_moderator, from_employee = excluded.from_employee,
                                from_admin = excluded.from_admin, updated_at = excluded.updated_at
                            """)
                    .param("action", rule.action()).param("enabled", rule.enabled()).param("push", rule.push())
                    .param("moderator", rule.fromModerator()).param("employee", rule.fromEmployee()).param("admin", rule.fromAdmin())
                    .param("now", Timestamp.from(clock.instant()))
                    .update();
        }
    }

    private static void requireAction(String action) {
        if (Arrays.stream(NotificationAction.values()).noneMatch(a -> a.name().equals(action))) {
            throw ApiException.badRequest("UNKNOWN_ACTION", "Unknown action: " + action);
        }
    }

    // ---- People ----

    /** Who gets an action, leaving out the person who did it. With nothing saved, admins do and nobody else. */
    List<Recipient> recipientsOf(NotificationAction action, long actorId) {
        return jdbc.sql("""
                        select u.id, u.role, s.can_see, s.can_push
                        from users u left join notification_subscriptions s on s.user_id = u.id and s.action = :action
                        where u.active and u.id <> :actor
                        """)
                .param("action", action.name()).param("actor", actorId)
                .query((rs, i) -> {
                    boolean admin = Role.ADMIN.name().equals(rs.getString(2));
                    Boolean see = rs.getObject(3, Boolean.class);
                    Boolean push = rs.getObject(4, Boolean.class);
                    boolean sees = see == null ? admin : see;
                    return sees ? new Recipient(rs.getLong(1), push == null ? admin : push) : null;
                })
                .list().stream().filter(java.util.Objects::nonNull).toList();
    }

    @Transactional(readOnly = true)
    public List<PersonView> people() {
        int total = NotificationAction.values().length;
        return jdbc.sql("""
                        select u.id, u.full_name, u.role,
                               (select count(*) from notification_subscriptions s where s.user_id = u.id and s.can_see),
                               (select count(*) from notification_subscriptions s where s.user_id = u.id and not s.can_see),
                               (select count(*) from notification_devices d where d.user_id = u.id)
                        from users u where u.active order by lower(u.full_name)
                        """)
                .query((rs, i) -> new PersonView(rs.getLong(1), rs.getString(2), rs.getString(3),
                        Role.ADMIN.name().equals(rs.getString(3)) ? total - rs.getLong(5) : rs.getLong(4), rs.getLong(6)))
                .list();
    }

    @Transactional(readOnly = true)
    public List<Subscription> subscriptionsOf(long userId) {
        boolean admin = load(userId).getRole() == Role.ADMIN;
        Map<String, Saved> saved = new HashMap<>();
        jdbc.sql("select action, can_see, can_push from notification_subscriptions where user_id = :user").param("user", userId)
                .query((rs, i) -> saved.put(rs.getString(1), new Saved(rs.getBoolean(2), rs.getBoolean(3)))).list();
        return Arrays.stream(NotificationAction.values()).map(a -> {
            Saved s = saved.getOrDefault(a.name(), new Saved(admin, admin));
            return new Subscription(a.name(), s.see(), s.see() && s.push());
        }).toList();
    }

    /** Whoever sees anything can open the menu, and nobody else, so the admin only sets this up in one place. */
    @Transactional
    public void saveSubscriptions(long userId, List<Subscription> subscriptions) {
        User user = load(userId);
        for (Subscription s : subscriptions) {
            requireAction(s.action());
            jdbc.sql("""
                            insert into notification_subscriptions (user_id, action, can_see, can_push) values (:user, :action, :see, :push)
                            on conflict (user_id, action) do update set can_see = excluded.can_see, can_push = excluded.can_push
                            """)
                    .param("user", userId).param("action", s.action()).param("see", s.see()).param("push", s.see() && s.push())
                    .update();
        }
        if (user.getRole() != Role.ADMIN) {
            Set<Permission> permissions = user.getPermissions().isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(user.getPermissions());
            if (subscriptions.stream().anyMatch(Subscription::see)) {
                permissions.add(Permission.NOTIFICATION_VIEW);
            } else {
                permissions.remove(Permission.NOTIFICATION_VIEW);
            }
            user.replacePermissions(permissions);
        }
    }

    private User load(long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("Staff member", id));
    }

    // ---- Firebase ----

    private record Settings(String projectId, String clientEmail, String encryptedKey, String webConfig, String vapidKey) {
    }

    private Optional<Settings> settings() {
        return jdbc.sql("select project_id, client_email, private_key_encrypted, web_config, vapid_key from notification_settings where id = 1")
                .query((rs, i) -> new Settings(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .optional();
    }

    @Transactional(readOnly = true)
    public SettingsView settingsView() {
        return settings().map(s -> {
            Map<String, String> web = parseWebConfig(s.webConfig());
            return new SettingsView(true, s.projectId(), s.clientEmail(), mask(web.get("apiKey")), web.get("appId"), web.get("messagingSenderId"), s.vapidKey());
        }).orElse(new SettingsView(false, null, null, null, null, null, null));
    }

    private static String mask(String value) {
        return value == null || value.length() < 8 ? "••••" : value.substring(0, 4) + "••••" + value.substring(value.length() - 4);
    }

    /** The private key is checked here and encrypted before it is stored. It is never sent back. */
    @Transactional
    public void saveSettings(SettingsRequest request) {
        Map<String, String> web = parseWebConfig(request.webConfig());
        for (String required : List.of("apiKey", "projectId", "messagingSenderId", "appId")) {
            if (web.get(required) == null) {
                throw ApiException.badRequest("INVALID_WEB_CONFIG", "The web app config is missing " + required);
            }
        }
        String serviceAccount = request.serviceAccount() == null ? "" : request.serviceAccount().trim();
        Optional<Settings> current = settings();
        if (serviceAccount.isEmpty() && current.isEmpty()) {
            throw ApiException.badRequest("SERVICE_ACCOUNT_REQUIRED", "Paste the service account key file");
        }
        String projectId;
        String clientEmail;
        String encrypted;
        if (serviceAccount.isEmpty()) {
            projectId = current.get().projectId();
            clientEmail = current.get().clientEmail();
            encrypted = current.get().encryptedKey();
        } else {
            JsonNode account = parseServiceAccount(serviceAccount);
            projectId = account.path("project_id").asString();
            clientEmail = account.path("client_email").asString();
            encrypted = cipher.encrypt(account.path("private_key").asString());
        }
        jdbc.sql("""
                        insert into notification_settings (id, project_id, client_email, private_key_encrypted, web_config, vapid_key, updated_at)
                        values (1, :project, :email, :key, :web, :vapid, :now)
                        on conflict (id) do update set project_id = excluded.project_id, client_email = excluded.client_email,
                            private_key_encrypted = excluded.private_key_encrypted, web_config = excluded.web_config,
                            vapid_key = excluded.vapid_key, updated_at = excluded.updated_at
                        """)
                .param("project", projectId).param("email", clientEmail).param("key", encrypted)
                .param("web", JSON.writeValueAsString(web)).param("vapid", request.vapidKey().trim())
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    private static JsonNode parseServiceAccount(String text) {
        JsonNode account;
        try {
            account = JSON.readTree(text);
        } catch (JacksonException ex) {
            throw ApiException.badRequest("INVALID_SERVICE_ACCOUNT", "That isn't a Firebase service account key file");
        }
        for (String field : List.of("project_id", "client_email", "private_key")) {
            if (account.path(field).asString("").isBlank()) {
                throw ApiException.badRequest("INVALID_SERVICE_ACCOUNT", "That isn't a Firebase service account key file");
            }
        }
        try {
            FcmClient.privateKey(account.path("private_key").asString());
        } catch (GeneralSecurityException ex) {
            throw ApiException.badRequest("INVALID_SERVICE_ACCOUNT", "The private key in that file can't be read");
        }
        return account;
    }

    /** Takes the snippet Firebase shows, or plain JSON. */
    private static Map<String, String> parseWebConfig(String text) {
        Map<String, String> config = new LinkedHashMap<>();
        Matcher matcher = CONFIG_ENTRY.matcher(text);
        while (matcher.find()) {
            config.put(matcher.group(1), matcher.group(2));
        }
        return config;
    }

    @Transactional(readOnly = true)
    public PushConfig pushConfig() {
        return settings().map(s -> new PushConfig(true, parseWebConfig(s.webConfig()), s.vapidKey()))
                .orElse(new PushConfig(false, Map.of(), null));
    }

    Optional<FcmClient.Credentials> credentials() {
        return settings().map(s -> new FcmClient.Credentials(s.projectId(), s.clientEmail(), cipher.decrypt(s.encryptedKey())));
    }

    // ---- Devices ----

    @Transactional
    public void addDevice(long userId, String token) {
        jdbc.sql("""
                        insert into notification_devices (token, user_id, created_at) values (:token, :user, :now)
                        on conflict (token) do update set user_id = excluded.user_id
                        """)
                .param("token", token).param("user", userId).param("now", Timestamp.from(clock.instant())).update();
    }

    @Transactional
    public void removeDevice(long userId, String token) {
        jdbc.sql("delete from notification_devices where token = :token and user_id = :user").param("token", token).param("user", userId).update();
    }
}
