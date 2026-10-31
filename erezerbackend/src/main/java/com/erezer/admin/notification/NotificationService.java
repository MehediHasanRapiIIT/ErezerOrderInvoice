package com.erezer.admin.notification;

import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.notification.NotificationDtos.NotificationView;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns what people do into notifications, following the admin's setup: the action's rule decides whether it notifies
 * and whose actions count, and each person's subscription decides whether they see it and get a push.
 */
@Service
public class NotificationService {

    /** The list's filters. */
    public record Filter(boolean unreadOnly, String section, Long actorId, LocalDate from, LocalDate to) {
    }

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Duration KEPT_FOR = Duration.ofDays(90);
    private static final int MAX_TITLE = 250;
    private static final int MAX_BODY = 600;

    private final JdbcClient jdbc;
    private final NotificationSetupService setup;
    private final FcmClient fcm;
    private final Clock clock;
    /** Pushes go out on the side, so nobody waits for Firebase after pressing Save. */
    private final ExecutorService pushes = Executors.newVirtualThreadPerTaskExecutor();

    NotificationService(JdbcClient jdbc, NotificationSetupService setup, FcmClient fcm, Clock clock) {
        this.jdbc = jdbc;
        this.setup = setup;
        this.fcm = fcm;
        this.clock = clock;
    }

    /** A line about a record, or null when it has none or no longer exists. */
    String describe(NotificationTarget target, long id) {
        return target.sql() == null ? null : jdbc.sql(target.sql()).param("id", id).query(String.class).optional().orElse(null);
    }

    /** Called after an action succeeded. */
    @Transactional
    public void record(NotificationAction action, long actorId, Long targetId, String summary, String detail) {
        record Actor(String name, String role) {
        }
        Actor actor = jdbc.sql("select full_name, role from users where id = :id").param("id", actorId)
                .query((rs, i) -> new Actor(rs.getString(1), rs.getString(2))).single();
        NotificationSetupService.RuleState rule = setup.ruleOf(action);
        if (!rule.enabled() || !rule.allows(actor.role())) {
            return;
        }
        List<NotificationSetupService.Recipient> recipients = setup.recipientsOf(action, actorId);
        if (recipients.isEmpty()) {
            return;
        }
        String title = limit(actor.name() + ": " + action.label(), MAX_TITLE);
        String body = limit(String.join(" · ", Arrays.stream(new String[] {summary, detail}).filter(s -> s != null && !s.isBlank()).toList()), MAX_BODY);
        String link = action.target().link(targetId, action.isDeletion());
        Instant now = clock.instant();
        long id = jdbc.sql("""
                        insert into notifications (action, actor_id, actor_name, actor_role, title, body, link, created_at)
                        values (:action, :actor, :name, :role, :title, :body, :link, :now) returning id
                        """)
                .param("action", action.name()).param("actor", actorId).param("name", actor.name()).param("role", actor.role())
                .param("title", title).param("body", body.isEmpty() ? null : body).param("link", link)
                .param("now", Timestamp.from(now))
                .query(Long.class).single();
        for (NotificationSetupService.Recipient recipient : recipients) {
            jdbc.sql("insert into notification_recipients (notification_id, user_id) values (:n, :u)")
                    .param("n", id).param("u", recipient.userId()).update();
        }
        if (rule.push()) {
            List<Long> pushTo = recipients.stream().filter(NotificationSetupService.Recipient::push).map(NotificationSetupService.Recipient::userId).toList();
            if (!pushTo.isEmpty()) {
                sendLater(pushTo, title, body, link);
            }
        }
    }

    private void sendLater(List<Long> userIds, String title, String body, String link) {
        pushes.submit(() -> {
            try {
                send(userIds, title, body, link);
            } catch (RuntimeException ex) {
                log.warn("Could not send pushes", ex);
            }
        });
    }

    /** @return how many devices took it and how many didn't */
    int[] send(List<Long> userIds, String title, String body, String link) {
        Optional<FcmClient.Credentials> credentials = setup.credentials();
        if (credentials.isEmpty()) {
            return new int[] {0, 0};
        }
        List<String> tokens = jdbc.sql("select token from notification_devices where user_id in (:users)")
                .param("users", userIds).query(String.class).list();
        int sent = 0;
        int failed = 0;
        Map<String, String> data = new HashMap<>();
        data.put("title", title);
        data.put("body", body == null ? "" : body);
        data.put("link", link == null ? "/notifications" : link);
        for (String token : tokens) {
            FcmClient.Result result = fcm.send(credentials.get(), token, data);
            if (result == FcmClient.Result.SENT) {
                sent++;
            } else {
                failed++;
                if (result == FcmClient.Result.INVALID_TOKEN) {
                    jdbc.sql("delete from notification_devices where token = :token").param("token", token).update();
                }
            }
        }
        return new int[] {sent, failed};
    }

    // ---- The person's own notifications ----

    @Transactional(readOnly = true)
    public PageResponse<NotificationView> list(long userId, Filter filter, int page, int size) {
        Map<String, Object> params = new HashMap<>();
        String where = where(userId, filter, params);
        long total = jdbc.sql("select count(*) from notification_recipients r join notifications n on n.id = r.notification_id" + where)
                .params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<NotificationView> rows = jdbc.sql("""
                        select n.id, n.action, n.title, n.body, n.link, n.actor_name, n.actor_role, n.created_at, r.read_at
                        from notification_recipients r join notifications n on n.id = r.notification_id
                        """ + where + " order by n.created_at desc, n.id desc limit :limit offset :offset")
                .params(params)
                .query((rs, i) -> new NotificationView(rs.getLong(1), rs.getString(2), sectionOf(rs.getString(2)), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant(),
                        rs.getTimestamp(9) != null))
                .list();
        return new PageResponse<>(rows, page, size, total, (int) Math.ceil((double) total / size));
    }

    private static String where(long userId, Filter f, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();
        conditions.add("r.user_id = :user");
        params.put("user", userId);
        if (f.unreadOnly()) {
            conditions.add("r.read_at is null");
        }
        if (f.section() != null && !f.section().isBlank()) {
            List<String> actions = Arrays.stream(NotificationAction.values()).filter(a -> a.section().equals(f.section())).map(Enum::name).toList();
            conditions.add(actions.isEmpty() ? "false" : "n.action in (:actions)");
            if (!actions.isEmpty()) {
                params.put("actions", actions);
            }
        }
        if (f.actorId() != null) {
            conditions.add("n.actor_id = :actor");
            params.put("actor", f.actorId());
        }
        if (f.from() != null) {
            conditions.add("n.created_at >= cast(:from as date)");
            params.put("from", Date.valueOf(f.from()));
        }
        if (f.to() != null) {
            conditions.add("n.created_at < cast(:to as date)");
            params.put("to", Date.valueOf(f.to().plusDays(1)));
        }
        return " where " + String.join(" and ", conditions);
    }

    private static String sectionOf(String action) {
        return Arrays.stream(NotificationAction.values()).filter(a -> a.name().equals(action)).findFirst()
                .map(NotificationAction::section).orElse("Other");
    }

    @Transactional(readOnly = true)
    public long unread(long userId) {
        return jdbc.sql("select count(*) from notification_recipients where user_id = :user and read_at is null")
                .param("user", userId).query(Long.class).single();
    }

    @Transactional
    public void markRead(long userId, long notificationId) {
        jdbc.sql("update notification_recipients set read_at = :now where user_id = :user and notification_id = :id and read_at is null")
                .param("now", Timestamp.from(clock.instant())).param("user", userId).param("id", notificationId).update();
    }

    @Transactional
    public void markAllRead(long userId) {
        jdbc.sql("update notification_recipients set read_at = :now where user_id = :user and read_at is null")
                .param("now", Timestamp.from(clock.instant())).param("user", userId).update();
    }

    /** Sends a test to the person's own devices. */
    public int[] test(long userId) {
        return send(List.of(userId), "Test notification", "Push notifications are working.", "/notifications");
    }

    @Scheduled(cron = "0 30 4 * * *", zone = "${app.time-zone}")
    @Transactional
    public void purgeOld() {
        jdbc.sql("delete from notifications where created_at < :cutoff")
                .param("cutoff", Timestamp.from(clock.instant().minus(KEPT_FOR))).update();
    }

    private static String limit(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
