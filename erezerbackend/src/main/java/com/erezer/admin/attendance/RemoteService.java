package com.erezer.admin.attendance;

import com.erezer.admin.attendance.RemoteDtos.DayView;
import com.erezer.admin.attendance.RemoteDtos.Me;
import com.erezer.admin.attendance.RemoteDtos.MonthCell;
import com.erezer.admin.attendance.RemoteDtos.MyMonth;
import com.erezer.admin.attendance.RemoteDtos.OffRequest;
import com.erezer.admin.attendance.RemoteDtos.OnRequest;
import com.erezer.admin.attendance.RemoteDtos.PersonCard;
import com.erezer.admin.attendance.RemoteDtos.SessionRequest;
import com.erezer.admin.attendance.RemoteDtos.SessionView;
import com.erezer.admin.attendance.RemoteDtos.Summary;
import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.file.FileService;
import com.erezer.admin.file.StoredFile;
import com.erezer.admin.notification.NotificationAction;
import com.erezer.admin.notification.NotificationService;
import com.erezer.admin.security.Role;
import com.erezer.admin.user.User;
import com.erezer.admin.user.UserDirectory;
import com.erezer.admin.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remote attendance. People working from home press Reply On and Reply Off, as often as they like; breaks don't count.
 * With hours set, the first Reply On after the start (plus grace) is late, and finishing before the end is early: both
 * need a reason and wait for the admin, who may add a late fee. A Reply Off that never came is closed by the system at
 * the end of the hours, or at midnight without hours, and flagged. All times come from the server, in Bangladesh time.
 */
@Service
public class RemoteService {

    private static final Logger log = LoggerFactory.getLogger(RemoteService.class);

    record Day(long id, long userId, LocalDate date, LocalTime shiftStart, LocalTime shiftEnd, int graceMinutes,
            int lateMinutes, int earlyMinutes, String lateReason, String earlyReason, Instant doneAt, boolean notTurnedOff,
            String reviewStatus, BigDecimal lateFee, Long reviewedBy, String reviewNote) {
    }

    record Session(long id, long dayId, long userId, Instant onAt, Instant offAt, boolean autoClosed, StoredFile image,
            String note, String offNote, String editNote) {

        long minutes(Instant now) {
            return Duration.between(onAt, offAt == null ? now : offAt).toMinutes();
        }
    }

    private static final String DAY_COLUMNS = """
            select id, user_id, work_date, shift_start, shift_end, grace_minutes, late_minutes, early_minutes, late_reason,
                   early_reason, done_at, not_turned_off, review_status, late_fee, reviewed_by, review_note
            from remote_days
            """;

    private final JdbcClient jdbc;
    private final AttendanceService attendance;
    private final UserRepository users;
    private final UserDirectory userDirectory;
    private final FileService files;
    private final NotificationService notifications;
    private final AppProperties appProperties;
    private final Clock clock;

    RemoteService(JdbcClient jdbc, AttendanceService attendance, UserRepository users, UserDirectory userDirectory, FileService files,
            NotificationService notifications, AppProperties appProperties, Clock clock) {
        this.jdbc = jdbc;
        this.attendance = attendance;
        this.users = users;
        this.userDirectory = userDirectory;
        this.files = files;
        this.notifications = notifications;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    // ---- Who works remotely ----

    public boolean isRemote(long userId) {
        return jdbc.sql("select count(*) from remote_workers where user_id = :id").param("id", userId).query(Long.class).single() > 0;
    }

    @Transactional
    public void setRemote(long userId, boolean remote) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Employee", userId));
        if (user.getRole() == Role.ADMIN) {
            throw ApiException.badRequest("ADMIN_NO_ATTENDANCE", "Admins don't give attendance");
        }
        if (remote) {
            jdbc.sql("insert into remote_workers (user_id, created_at) values (:id, :now) on conflict do nothing")
                    .param("id", userId).param("now", Timestamp.from(clock.instant())).update();
        } else {
            jdbc.sql("delete from remote_workers where user_id = :id").param("id", userId).update();
        }
    }

    // ---- Reply On and Off ----

    /** Pressing it again while already On changes nothing. */
    @Transactional
    public Me on(long userId, OnRequest request) {
        requireRemote(userId);
        openSession(userId).ifPresent(s -> closeIfDue(s, clock.instant()));
        if (openSession(userId).isPresent()) {
            return me(userId);
        }
        LocalDate date = today();
        Day day = findDay(userId, date).orElse(null);
        StoredFile image = request.imageFileId() == null ? null : files.requireRemoteImage(request.imageFileId());
        Instant now = clock.instant();
        if (day == null) {
            Optional<AttendanceService.Hours> hours = attendance.hoursFor(userId, date);
            boolean working = hours.isPresent() && !hours.get().off();
            LocalTime start = working ? hours.get().start() : null;
            LocalTime end = working ? hours.get().end() : null;
            int grace = working ? hours.get().grace() : 0;
            LocalTime time = nowTime();
            int late = start != null && time.isAfter(start.plusMinutes(grace)) ? minutes(start, time) : 0;
            String reason = blankToNull(request.reason());
            if (late > 0 && reason == null) {
                throw ApiException.badRequest("REASON_REQUIRED", "You are " + late + " minutes late. Please give a reason.");
            }
            long id = jdbc.sql("""
                            insert into remote_days (user_id, work_date, shift_start, shift_end, grace_minutes, late_minutes, late_reason,
                                                     review_status, created_at, updated_at)
                            values (:user, :date, :start, :end, :grace, :late, :reason, :status, :now, :now) returning id
                            """)
                    .param("user", userId).param("date", Date.valueOf(date)).param("start", start).param("end", end)
                    .param("grace", grace).param("late", late).param("reason", late > 0 ? reason : null)
                    .param("status", late > 0 ? "PENDING" : "NONE").param("now", Timestamp.from(now))
                    .query(Long.class).single();
            day = loadDay(id);
        } else if (day.doneAt() != null) {
            // Back after "Done for today": the day isn't finished after all.
            jdbc.sql("update remote_days set done_at = null, updated_at = :now where id = :id")
                    .param("now", Timestamp.from(now)).param("id", day.id()).update();
        }
        try {
            jdbc.sql("""
                            insert into remote_sessions (day_id, user_id, on_at, image_id, note, created_at, updated_at)
                            values (:day, :user, :now, :image, :note, :now, :now)
                            """)
                    .param("day", day.id()).param("user", userId).param("now", Timestamp.from(now))
                    .param("image", image == null ? null : image.getId()).param("note", blankToNull(request.note()))
                    .update();
        } catch (DuplicateKeyException ex) {
            // Two presses at the same moment: the other one opened it.
        }
        recalc(day.id());
        return me(userId);
    }

    /** Pressing it again while already Off changes nothing. */
    @Transactional
    public Me off(long userId, OffRequest request) {
        requireRemote(userId);
        openSession(userId).ifPresent(s -> closeIfDue(s, clock.instant()));
        Optional<Session> open = openSession(userId);
        if (open.isEmpty()) {
            return me(userId);
        }
        Day day = loadDay(open.get().dayId());
        Instant now = clock.instant();
        if (request.done() && day.shiftEnd() != null && day.date().equals(today())) {
            LocalTime time = nowTime();
            int early = time.isBefore(day.shiftEnd()) ? minutes(time, day.shiftEnd()) : 0;
            String reason = blankToNull(request.reason());
            if (early > 0 && reason == null) {
                throw ApiException.badRequest("REASON_REQUIRED", "You are finishing " + early + " minutes early. Please give a reason.");
            }
            jdbc.sql("update remote_days set early_reason = :reason where id = :id")
                    .param("reason", early > 0 ? reason : null).param("id", day.id()).update();
        }
        jdbc.sql("update remote_sessions set off_at = :now, off_note = :note, updated_at = :now where id = :id")
                .param("now", Timestamp.from(now)).param("note", blankToNull(request.note())).param("id", open.get().id()).update();
        if (request.done()) {
            jdbc.sql("update remote_days set done_at = :now where id = :id").param("now", Timestamp.from(now)).param("id", day.id()).update();
        }
        recalc(day.id());
        return me(userId);
    }

    /**
     * Closes sessions nobody turned off, at the end of the day's hours or at midnight without hours, and marks days that
     * finished early once their hours are over. Runs every few minutes.
     */
    @Scheduled(cron = "0 */5 * * * *", zone = "${app.time-zone}")
    @Transactional
    public void closeDue() {
        Instant now = clock.instant();
        sessions("where s.off_at is null", Map.of()).forEach(session -> closeIfDue(session, now));
        // A break that never ended counts as finishing early once the hours are over.
        jdbc.sql("""
                        select d.id from remote_days d
                        where d.shift_end is not null and d.work_date >= :since and d.early_minutes = 0 and d.done_at is null
                          and not exists (select 1 from remote_sessions s where s.day_id = d.id and s.off_at is null)
                        """)
                .param("since", Date.valueOf(today().minusDays(3)))
                .query(Long.class).list()
                .forEach(this::recalc);
    }

    /** Closes a session left open past the end of its hours, or past midnight without hours, and flags the day. */
    private void closeIfDue(Session session, Instant now) {
        Day day = loadDay(session.dayId());
        Instant endOfHours = day.shiftEnd() == null ? null : instant(day.date(), day.shiftEnd());
        Instant midnight = day.date().plusDays(1).atStartOfDay(appProperties.zoneId()).toInstant();
        Instant cutoff = endOfHours != null && endOfHours.isAfter(session.onAt()) ? endOfHours : midnight;
        if (now.isBefore(cutoff)) {
            return;
        }
        jdbc.sql("update remote_sessions set off_at = :at, auto_closed = true, updated_at = :now where id = :id")
                .param("at", Timestamp.from(cutoff)).param("now", Timestamp.from(now)).param("id", session.id()).update();
        recalc(day.id());
        try {
            notifications.record(NotificationAction.REMOTE_NOT_TURNED_OFF, session.userId(), day.id(), describe(day.id()),
                    "Closed by the system at " + time(cutoff));
        } catch (RuntimeException ex) {
            log.warn("Could not record the forgotten Reply Off", ex);
        }
    }

    // ---- The person's own page ----

    @Transactional(readOnly = true)
    public Me me(long userId) {
        LocalDate date = today();
        boolean remote = isRemote(userId);
        Optional<AttendanceService.Hours> hours = attendance.hoursFor(userId, date);
        Optional<Day> day = findDay(userId, date);
        DayView view = day.map(d -> views(List.of(d)).getFirst()).orElse(null);
        Optional<Session> open = openSession(userId);
        return new Me(date, remote, hours.map(AttendanceService.Hours::start).orElse(null), hours.map(AttendanceService.Hours::end).orElse(null),
                hours.isPresent() && hours.get().off(), open.isPresent(), open.map(Session::onAt).orElse(null),
                view == null ? 0 : view.workedMinutes(), view);
    }

    @Transactional(readOnly = true)
    public MyMonth myMonth(long userId, YearMonth month) {
        Map<LocalDate, DayView> byDate = views(days(month, userId)).stream().collect(Collectors.toMap(DayView::date, Function.identity()));
        List<MonthCell> cells = new ArrayList<>();
        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
            DayView view = byDate.get(d);
            cells.add(view != null ? new MonthCell(d, view.status(), view.workedMinutes(), view.lateFee())
                    : new MonthCell(d, emptyStatus(userId, d), 0, BigDecimal.ZERO));
        }
        return new MyMonth(summary(month, userId), cells);
    }

    // ---- The admin's pages ----

    @Transactional(readOnly = true)
    public Summary summary(YearMonth month, Long userId) {
        return summarize(views(days(month, userId)));
    }

    /** One card per remote worker, with who is On right now. */
    @Transactional(readOnly = true)
    public PageResponse<PersonCard> people(YearMonth month, int page, int size) {
        Map<Long, List<DayView>> byUser = views(days(month, null)).stream().collect(Collectors.groupingBy(DayView::userId));
        Map<Long, Instant> openSince = new HashMap<>();
        sessions("where s.off_at is null", Map.of()).forEach(s -> openSince.put(s.userId(), s.onAt()));
        List<PersonCard> cards = workers().stream().map(w -> {
            Summary s = summarize(byUser.getOrDefault(w.getKey(), List.of()));
            Instant since = openSince.get(w.getKey());
            return new PersonCard(w.getKey(), w.getValue(), since != null, since, s.workedMinutes(), s.daysWorked(), s.lateDays(),
                    s.earlyDays(), s.notTurnedOff(), s.lateFees());
        }).toList();
        return slice(cards, page, size);
    }

    /** Everyone on one day, or one person's month day by day (newest first). */
    @Transactional(readOnly = true)
    public PageResponse<DayView> days(LocalDate day, YearMonth month, Long userId, int page, int size) {
        LocalDate date = day == null ? today() : day;
        if (userId != null) {
            List<DayView> list = new ArrayList<>(views(days(month == null ? YearMonth.from(date) : month, userId)));
            list.sort(Comparator.comparing(DayView::date).reversed());
            return slice(list, page, size);
        }
        Map<Long, DayView> byUser = views(jdbcDays("where work_date = :date", Map.of("date", Date.valueOf(date)))).stream()
                .collect(Collectors.toMap(DayView::userId, Function.identity()));
        List<DayView> rows = workers().stream().map(w -> byUser.getOrDefault(w.getKey(), emptyDay(w.getKey(), w.getValue(), date))).toList();
        return slice(rows, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<DayView> pending(Long userId, int page, int size) {
        Map<String, Object> params = new HashMap<>();
        String where = "where review_status = 'PENDING'";
        if (userId != null) {
            where += " and user_id = :user";
            params.put("user", userId);
        }
        List<DayView> list = new ArrayList<>(views(jdbcDays(where, params)));
        list.sort(Comparator.comparing(DayView::date).reversed());
        return slice(list, page, size);
    }

    /** Approves a day, with a late fee if given; also changes or removes (0) the fee of any day. */
    @Transactional
    public DayView review(long dayId, BigDecimal fee, String note, long adminId) {
        Day day = requireDay(dayId);
        requireNotOwn(day.userId(), adminId);
        jdbc.sql("""
                        update remote_days set review_status = 'APPROVED', late_fee = :fee, review_note = :note, reviewed_by = :by,
                            reviewed_at = :now, updated_at = :now where id = :id
                        """)
                .param("fee", (fee == null ? BigDecimal.ZERO : fee).setScale(2, RoundingMode.HALF_UP)).param("note", blankToNull(note))
                .param("by", adminId).param("now", Timestamp.from(clock.instant())).param("id", dayId).update();
        return views(List.of(loadDay(dayId))).getFirst();
    }

    @Transactional
    public DayView addSession(SessionRequest request, long adminId) {
        requireNotOwn(request.userId(), adminId);
        if (!isRemote(request.userId())) {
            throw ApiException.badRequest("NOT_REMOTE", "This person doesn't work remotely");
        }
        Instant on = instant(request.date(), request.on());
        Instant off = request.off() == null ? null : instant(request.date(), request.off());
        requireTimes(on, off, request.date());
        Day day = findDay(request.userId(), request.date()).orElseGet(() -> startDay(request.userId(), request.date()));
        requireNoOverlap(request.userId(), on, off, 0);
        try {
            jdbc.sql("""
                            insert into remote_sessions (day_id, user_id, on_at, off_at, edited_by, edit_note, created_at, updated_at)
                            values (:day, :user, :on, :off, :by, :note, :now, :now)
                            """)
                    .param("day", day.id()).param("user", request.userId()).param("on", Timestamp.from(on))
                    .param("off", off == null ? null : Timestamp.from(off)).param("by", adminId).param("note", request.note().trim())
                    .param("now", Timestamp.from(clock.instant())).update();
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("ALREADY_ON", "This person is already On. Give the session an end time.");
        }
        recalc(day.id());
        return views(List.of(loadDay(day.id()))).getFirst();
    }

    @Transactional
    public DayView updateSession(long id, SessionRequest request, long adminId) {
        Session session = requireSession(id);
        requireNotOwn(session.userId(), adminId);
        Day day = loadDay(session.dayId());
        Instant on = instant(day.date(), request.on());
        Instant off = request.off() == null ? null : instant(day.date(), request.off());
        requireTimes(on, off, day.date());
        requireNoOverlap(session.userId(), on, off, id);
        try {
            jdbc.sql("""
                            update remote_sessions set on_at = :on, off_at = :off, auto_closed = false, edited_by = :by, edit_note = :note,
                                updated_at = :now where id = :id
                            """)
                    .param("on", Timestamp.from(on)).param("off", off == null ? null : Timestamp.from(off)).param("by", adminId)
                    .param("note", request.note().trim()).param("now", Timestamp.from(clock.instant())).param("id", id).update();
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("ALREADY_ON", "This person is already On. Give the session an end time.");
        }
        recalc(day.id());
        return views(List.of(loadDay(day.id()))).getFirst();
    }

    /** Removes one session; a day left with none is removed too. */
    @Transactional
    public void deleteSession(long id, long adminId) {
        Session session = requireSession(id);
        requireNotOwn(session.userId(), adminId);
        jdbc.sql("delete from remote_sessions where id = :id").param("id", id).update();
        boolean empty = jdbc.sql("select count(*) from remote_sessions where day_id = :day").param("day", session.dayId())
                .query(Long.class).single() == 0;
        if (empty) {
            jdbc.sql("delete from remote_days where id = :id").param("id", session.dayId()).update();
        } else {
            recalc(session.dayId());
        }
    }

    @Transactional
    public void deleteDay(long dayId, long adminId) {
        Day day = requireDay(dayId);
        requireNotOwn(day.userId(), adminId);
        jdbc.sql("delete from remote_days where id = :id").param("id", dayId).update();
    }

    /** The cards for each remote worker in a month, for Excel. */
    @Transactional(readOnly = true)
    public List<PersonCard> exportRows(YearMonth month) {
        PageResponse<PersonCard> all = people(month, 0, Integer.MAX_VALUE / 2);
        return all.content();
    }

    // ---- Rules ----

    /**
     * Works the day out again from its sessions: late from the first Reply On, early from the last Reply Off once the
     * day is finished (Done pressed, or the hours are over), and whether the system had to close a session. New
     * problems wait for the admin; a day with none left goes back to normal unless it has a fee.
     */
    void recalc(long dayId) {
        Day day = loadDay(dayId);
        List<Session> list = sessions("where s.day_id = :day", Map.of("day", dayId));
        if (list.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        Session first = list.stream().min(Comparator.comparing(Session::onAt)).orElseThrow();
        boolean anyOpen = list.stream().anyMatch(s -> s.offAt() == null);
        int late = 0;
        int early = 0;
        if (day.shiftStart() != null) {
            LocalTime firstOn = localTime(first.onAt());
            late = firstOn.isAfter(day.shiftStart().plusMinutes(day.graceMinutes())) ? minutes(day.shiftStart(), firstOn) : 0;
            Instant end = instant(day.date(), day.shiftEnd());
            boolean finished = !anyOpen && (day.doneAt() != null || !now.isBefore(end));
            if (finished) {
                Instant lastOff = list.stream().map(Session::offAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElseThrow();
                early = lastOff.isBefore(end) ? (int) Duration.between(lastOff, end).toMinutes() : 0;
            }
        }
        boolean notTurnedOff = list.stream().anyMatch(Session::autoClosed);
        boolean problem = late > 0 || early > 0 || notTurnedOff;
        boolean worse = late > day.lateMinutes() || early > day.earlyMinutes() || (notTurnedOff && !day.notTurnedOff());
        String status = day.reviewStatus();
        if (problem && ("NONE".equals(status) || worse)) {
            status = "PENDING";
        } else if (!problem) {
            status = day.lateFee().signum() > 0 ? "APPROVED" : "NONE";
        }
        jdbc.sql("""
                        update remote_days set late_minutes = :late, early_minutes = :early, not_turned_off = :forgot, review_status = :status,
                            late_reason = case when :late = 0 then null else late_reason end,
                            early_reason = case when :early = 0 then null else early_reason end,
                            updated_at = :now where id = :id
                        """)
                .param("late", late).param("early", early).param("forgot", notTurnedOff).param("status", status)
                .param("now", Timestamp.from(now)).param("id", dayId).update();
    }

    private Day startDay(long userId, LocalDate date) {
        Optional<AttendanceService.Hours> hours = attendance.hoursFor(userId, date);
        boolean working = hours.isPresent() && !hours.get().off();
        long id = jdbc.sql("""
                        insert into remote_days (user_id, work_date, shift_start, shift_end, grace_minutes, created_at, updated_at)
                        values (:user, :date, :start, :end, :grace, :now, :now) returning id
                        """)
                .param("user", userId).param("date", Date.valueOf(date)).param("start", working ? hours.get().start() : null)
                .param("end", working ? hours.get().end() : null).param("grace", working ? hours.get().grace() : 0)
                .param("now", Timestamp.from(clock.instant())).query(Long.class).single();
        return loadDay(id);
    }

    private void requireRemote(long userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Employee", userId));
        if (user.getRole() == Role.ADMIN) {
            throw ApiException.conflict("ADMIN_NO_ATTENDANCE", "Admins don't give attendance");
        }
        if (!isRemote(userId)) {
            throw ApiException.conflict("NOT_REMOTE", "You give attendance at the office. Use Check In instead.");
        }
    }

    private void requireTimes(Instant on, Instant off, LocalDate date) {
        if (off != null && !off.isAfter(on)) {
            throw ApiException.badRequest("INVALID_TIMES", "Reply Off must be after Reply On");
        }
        if (on.isAfter(clock.instant()) || (off != null && off.isAfter(clock.instant()))) {
            throw ApiException.badRequest("FUTURE_TIME", "The times can't be in the future");
        }
        if (off == null && !date.equals(today())) {
            throw ApiException.badRequest("OFF_REQUIRED", "Give the Reply Off time");
        }
    }

    private void requireNoOverlap(long userId, Instant on, Instant off, long exceptId) {
        Instant end = off == null ? clock.instant() : off;
        long clashes = jdbc.sql("""
                        select count(*) from remote_sessions
                        where user_id = :user and id <> :except and on_at < :end and coalesce(off_at, :now) > :start
                        """)
                .param("user", userId).param("except", exceptId).param("end", Timestamp.from(end)).param("start", Timestamp.from(on))
                .param("now", Timestamp.from(clock.instant())).query(Long.class).single();
        if (clashes > 0) {
            throw ApiException.conflict("OVERLAP", "These times overlap another session");
        }
    }

    private static void requireNotOwn(long employeeId, long actorId) {
        if (employeeId == actorId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWN_ATTENDANCE", "You can't change your own attendance");
        }
    }

    private static Summary summarize(List<DayView> days) {
        long worked = days.stream().mapToLong(DayView::workedMinutes).sum();
        long daysWorked = days.stream().filter(d -> !d.sessions().isEmpty()).count();
        long late = days.stream().filter(d -> d.lateMinutes() > 0).count();
        long early = days.stream().filter(d -> d.earlyMinutes() > 0).count();
        long forgot = days.stream().filter(DayView::notTurnedOff).count();
        BigDecimal fees = days.stream().map(DayView::lateFee).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Summary(worked, daysWorked, late, early, forgot, fees);
    }

    /** A day with nothing recorded: off, still to come, missed, or nothing expected without hours. */
    private String emptyStatus(long userId, LocalDate date) {
        Optional<AttendanceService.Hours> hours = attendance.hoursFor(userId, date);
        if (hours.isEmpty()) {
            return "NONE";
        }
        if (hours.get().off()) {
            return "OFF";
        }
        LocalDate today = today();
        if (date.isAfter(today) || (date.equals(today) && nowTime().isBefore(hours.get().end()))) {
            return "WAITING";
        }
        return "ABSENT";
    }

    private DayView emptyDay(long userId, String name, LocalDate date) {
        Optional<AttendanceService.Hours> hours = attendance.hoursFor(userId, date);
        return new DayView(null, userId, name, date, hours.map(AttendanceService.Hours::start).orElse(null),
                hours.map(AttendanceService.Hours::end).orElse(null), emptyStatus(userId, date), 0, 0, null, null, false, "NONE",
                BigDecimal.ZERO, null, null, 0, false, List.of());
    }

    private List<DayView> views(List<Day> days) {
        if (days.isEmpty()) {
            return List.of();
        }
        List<Long> ids = days.stream().map(Day::id).toList();
        Map<Long, List<Session>> byDay = sessions("where s.day_id in (:days)", Map.of("days", ids)).stream()
                .collect(Collectors.groupingBy(Session::dayId));
        List<Long> people = new ArrayList<>();
        days.forEach(d -> {
            people.add(d.userId());
            if (d.reviewedBy() != null) {
                people.add(d.reviewedBy());
            }
        });
        Map<Long, String> names = userDirectory.namesById(people);
        Instant now = clock.instant();
        return days.stream().map(d -> {
            List<Session> list = byDay.getOrDefault(d.id(), List.of()).stream().sorted(Comparator.comparing(Session::onAt)).toList();
            boolean on = list.stream().anyMatch(s -> s.offAt() == null);
            long worked = list.stream().mapToLong(s -> s.minutes(now)).sum();
            return new DayView(d.id(), d.userId(), UserDirectory.nameOf(names, d.userId()), d.date(), d.shiftStart(), d.shiftEnd(),
                    status(d, on), d.lateMinutes(), d.earlyMinutes(), d.lateReason(), d.earlyReason(), d.notTurnedOff(), d.reviewStatus(),
                    d.lateFee(), d.reviewedBy() == null ? null : UserDirectory.nameOf(names, d.reviewedBy()), d.reviewNote(), worked, on,
                    list.stream().map(s -> new SessionView(s.id(), s.onAt(), s.offAt(), s.minutes(now), s.autoClosed(),
                            s.image() == null ? null : files.url(s.image()), s.note(), s.offNote(), s.editNote())).toList());
        }).toList();
    }

    private static String status(Day d, boolean on) {
        if (on) {
            return "WORKING";
        }
        if (d.notTurnedOff()) {
            return "NOT_TURNED_OFF";
        }
        boolean late = d.lateMinutes() > 0;
        boolean early = d.earlyMinutes() > 0;
        return late && early ? "LATE_EARLY" : late ? "LATE" : early ? "EARLY" : "PRESENT";
    }

    /** Active remote workers, by name. */
    private List<Map.Entry<Long, String>> workers() {
        return jdbc.sql("""
                        select u.id, u.full_name from remote_workers w join users u on u.id = w.user_id
                        where u.active and u.role <> 'ADMIN' order by lower(u.full_name)
                        """)
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getString(2))).list();
    }

    private List<Day> days(YearMonth month, Long userId) {
        Map<String, Object> params = new HashMap<>();
        params.put("from", Date.valueOf(month.atDay(1)));
        params.put("to", Date.valueOf(month.atEndOfMonth()));
        String where = "where work_date between :from and :to";
        if (userId != null) {
            where += " and user_id = :user";
            params.put("user", userId);
        }
        return jdbcDays(where, params);
    }

    private List<Day> jdbcDays(String where, Map<String, Object> params) {
        return jdbc.sql(DAY_COLUMNS + where).params(params)
                .query((rs, i) -> new Day(rs.getLong(1), rs.getLong(2), rs.getObject(3, LocalDate.class), rs.getObject(4, LocalTime.class),
                        rs.getObject(5, LocalTime.class), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getString(9), rs.getString(10),
                        rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant(), rs.getBoolean(12), rs.getString(13),
                        rs.getBigDecimal(14), rs.getObject(15, Long.class), rs.getString(16)))
                .list();
    }

    private Optional<Day> findDay(long userId, LocalDate date) {
        return jdbcDays("where user_id = :user and work_date = :date", Map.of("user", userId, "date", Date.valueOf(date))).stream().findFirst();
    }

    private Day loadDay(long id) {
        return requireDay(id);
    }

    private Day requireDay(long id) {
        return jdbcDays("where id = :id", Map.of("id", id)).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Remote attendance day", id));
    }

    private List<Session> sessions(String where, Map<String, Object> params) {
        List<Session> list = jdbc.sql("""
                        select s.id, s.day_id, s.user_id, s.on_at, s.off_at, s.auto_closed, s.image_id, s.note, s.off_note, s.edit_note
                        from remote_sessions s
                        """ + where + " order by s.on_at")
                .params(params)
                .query((rs, i) -> new Object[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getTimestamp(4).toInstant(),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(), rs.getBoolean(6), rs.getObject(7, Long.class),
                        rs.getString(8), rs.getString(9), rs.getString(10)})
                .list().stream().map(r -> new Session((Long) r[0], (Long) r[1], (Long) r[2], (Instant) r[3], (Instant) r[4],
                        (Boolean) r[5], r[6] == null ? null : files.find((Long) r[6]), (String) r[7], (String) r[8], (String) r[9]))
                .toList();
        return list;
    }

    private Optional<Session> openSession(long userId) {
        return sessions("where s.user_id = :user and s.off_at is null", Map.of("user", userId)).stream().findFirst();
    }

    private Session requireSession(long id) {
        return sessions("where s.id = :id", Map.of("id", id)).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Session", id));
    }

    private String describe(long dayId) {
        return jdbc.sql("select u.full_name || ' · ' || d.work_date from remote_days d join users u on u.id = d.user_id where d.id = :id")
                .param("id", dayId).query(String.class).optional().orElse(null);
    }

    private static <T> PageResponse<T> slice(List<T> all, int page, int size) {
        int from = (int) Math.min((long) page * size, all.size());
        int to = (int) Math.min((long) from + size, all.size());
        return new PageResponse<>(all.subList(from, to), page, size, all.size(), (int) Math.ceil((double) all.size() / size));
    }

    private static int minutes(LocalTime from, LocalTime to) {
        return (int) Duration.between(from, to).toMinutes();
    }

    private Instant instant(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(appProperties.zoneId()).toInstant();
    }

    private LocalTime localTime(Instant instant) {
        return instant.atZone(appProperties.zoneId()).toLocalTime().truncatedTo(ChronoUnit.MINUTES);
    }

    private String time(Instant instant) {
        return localTime(instant).toString();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(appProperties.zoneId()));
    }

    private LocalTime nowTime() {
        return LocalTime.now(clock.withZone(appProperties.zoneId())).truncatedTo(ChronoUnit.MINUTES);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
