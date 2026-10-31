package com.erezer.admin.attendance;

import com.erezer.admin.attendance.AttendanceDtos.CheckInRequest;
import com.erezer.admin.attendance.AttendanceDtos.CheckOutRequest;
import com.erezer.admin.attendance.AttendanceDtos.DayCell;
import com.erezer.admin.attendance.AttendanceDtos.DayCorrection;
import com.erezer.admin.attendance.AttendanceDtos.DayRow;
import com.erezer.admin.attendance.AttendanceDtos.EmployeeOption;
import com.erezer.admin.attendance.AttendanceDtos.LocationRequest;
import com.erezer.admin.attendance.AttendanceDtos.LocationView;
import com.erezer.admin.attendance.AttendanceDtos.MonthRow;
import com.erezer.admin.attendance.AttendanceDtos.MonthSheet;
import com.erezer.admin.attendance.AttendanceDtos.MyToday;
import com.erezer.admin.attendance.AttendanceDtos.RecordView;
import com.erezer.admin.attendance.AttendanceDtos.ReviewRequest;
import com.erezer.admin.attendance.AttendanceDtos.ShiftRequest;
import com.erezer.admin.attendance.AttendanceDtos.ShiftView;
import com.erezer.admin.attendance.AttendanceDtos.SpecialHoursRequest;
import com.erezer.admin.attendance.AttendanceDtos.SpecialHoursView;
import com.erezer.admin.attendance.AttendanceDtos.Summary;
import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.file.FileService;
import com.erezer.admin.file.StoredFile;
import com.erezer.admin.security.Role;
import com.erezer.admin.user.User;
import com.erezer.admin.user.UserDirectory;
import com.erezer.admin.user.UserRepository;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attendance for everyone but admins. Everyone has their own shift. Checking in needs a selfie and the phone's position; the
 * nearest of the admin's locations is recorded. Being late, leaving early or being away from every location needs a
 * reason, and the day then waits for the admin, who approves it, with a late fee if they choose.
 *
 * <p>All times come from the server in Bangladesh time, so a phone's clock has no effect.
 */
@Service
public class AttendanceService {

    /** A row of the employees taking part: active non-admins with a shift. */
    record Shift(long userId, String fullName, LocalTime checkIn, LocalTime checkOut, int graceMinutes,
            Set<DayOfWeek> weeklyOff, LocalDate startsOn) {
    }

    /** Hours that differ from the normal shift. No user means everyone. */
    private record Special(long id, Long userId, String personName, DayOfWeek weekDay, LocalDate from, LocalDate to, boolean off,
            LocalTime checkIn, LocalTime checkOut, String note) {
    }

    /** What applies to someone on one day. */
    private record Day(LocalTime checkIn, LocalTime checkOut, boolean off) {
    }

    private record Location(long id, String name, Double latitude, Double longitude, int radiusM, boolean active) {
    }

    private final AttendanceRecordRepository records;
    private final UserRepository users;
    private final UserDirectory userDirectory;
    private final FileService files;
    private final JdbcClient jdbc;
    private final AppProperties appProperties;
    private final Clock clock;

    AttendanceService(AttendanceRecordRepository records, UserRepository users, UserDirectory userDirectory,
            FileService files, JdbcClient jdbc, AppProperties appProperties, Clock clock) {
        this.records = records;
        this.users = users;
        this.userDirectory = userDirectory;
        this.files = files;
        this.jdbc = jdbc;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    // ---- The employee's own page ----

    @Transactional(readOnly = true)
    public MyToday today(long userId) {
        LocalDate date = today();
        Optional<Shift> shift = shiftOf(userId);
        RecordView view = records.findByUserIdAndWorkDate(userId, date).map(r -> toViews(List.of(r)).getFirst()).orElse(null);
        Optional<Day> day = shift.map(s -> dayOf(s, date, specials()));
        return new MyToday(date, shift.map(AttendanceService::toView).orElse(null), day.isPresent() && day.get().off(), view,
                day.map(Day::checkIn).orElse(null), day.map(Day::checkOut).orElse(null));
    }

    @Transactional
    public RecordView checkIn(long userId, CheckInRequest request) {
        Shift shift = requireEmployee(userId);
        LocalDate date = today();
        if (records.findByUserIdAndWorkDate(userId, date).isPresent()) {
            throw ApiException.conflict("ALREADY_CHECKED_IN", "You have already checked in today");
        }
        StoredFile photo = files.requireSelfie(request.photoFileId());
        AttendanceRecord.Place place = place(request.latitude(), request.longitude());

        LocalTime now = nowTime();
        Day day = dayOf(shift, date, specials());
        boolean late = !day.off() && now.isAfter(day.checkIn().plusMinutes(shift.graceMinutes()));
        int lateMinutes = late ? minutes(day.checkIn(), now) : 0;
        String reason = blankToNull(request.reason());
        if ((late || !place.inRange()) && reason == null) {
            throw reasonRequired(late ? "You are " + lateMinutes + " minutes late." : null, place);
        }
        AttendanceRecord record = new AttendanceRecord(userId, date, day.checkIn(), day.checkOut());
        record.checkIn(clock.instant(), place, photo, reason, lateMinutes, late || !place.inRange());
        return toViews(List.of(records.save(record))).getFirst();
    }

    @Transactional
    public RecordView checkOut(long userId, CheckOutRequest request) {
        Shift shift = requireEmployee(userId);
        AttendanceRecord record = records.findByUserIdAndWorkDate(userId, today())
                .orElseThrow(() -> ApiException.conflict("NOT_CHECKED_IN", "Check in first"));
        if (record.getCheckOutAt() != null) {
            throw ApiException.conflict("ALREADY_CHECKED_OUT", "You have already checked out today");
        }
        AttendanceRecord.Place place = place(request.latitude(), request.longitude());
        LocalTime now = nowTime();
        boolean early = !dayOf(shift, today(), specials()).off() && now.isBefore(record.getShiftEnd());
        int earlyMinutes = early ? minutes(now, record.getShiftEnd()) : 0;
        String reason = blankToNull(request.reason());
        if ((early || !place.inRange()) && reason == null) {
            throw reasonRequired(early ? "You are leaving " + earlyMinutes + " minutes early." : null, place);
        }
        record.checkOut(clock.instant(), place, reason, earlyMinutes, early || !place.inRange());
        return toViews(List.of(record)).getFirst();
    }

    /** One employee's month, for their own page. */
    @Transactional(readOnly = true)
    public MonthRow myMonth(long userId, YearMonth month) {
        Optional<Shift> shift = shiftOf(userId);
        if (shift.isEmpty()) {
            return new MonthRow(userId, "", List.of(), 0, 0, 0, 0, 0, BigDecimal.ZERO, 0);
        }
        return monthRow(shift.get(), month, records.findByUserIdAndWorkDateBetween(userId, month.atDay(1), month.atEndOfMonth()), specials());
    }

    // ---- The admin's pages ----

    /** The people the lists can be filtered by. */
    @Transactional(readOnly = true)
    public List<EmployeeOption> employeeOptions() {
        return employees().stream().map(s -> new EmployeeOption(s.userId(), s.fullName())).toList();
    }

    /** Total hours worked and late fees for a month: everyone's, or one employee's. */
    @Transactional(readOnly = true)
    public Summary summary(YearMonth month, Long userId) {
        List<AttendanceRecord> list = userId == null
                ? records.findByWorkDateBetween(month.atDay(1), month.atEndOfMonth())
                : records.findByUserIdAndWorkDateBetween(userId, month.atDay(1), month.atEndOfMonth());
        return new Summary(workedMinutes(list), lateFees(list));
    }

    /** Everyone on one day. */
    @Transactional(readOnly = true)
    public PageResponse<DayRow> day(LocalDate date, int page, int size) {
        List<AttendanceRecord> list = records.findByWorkDate(date);
        Map<Long, RecordView> byUser = toViews(list).stream().collect(Collectors.toMap(RecordView::userId, r -> r));
        Map<Long, AttendanceRecord> raw = list.stream().collect(Collectors.toMap(AttendanceRecord::getUserId, r -> r));
        List<Special> specials = specials();
        return slice(employees(), page, size, s -> {
            Day day = dayOf(s, date, specials);
            return new DayRow(s.userId(), s.fullName(), date, day.checkIn(), day.checkOut(),
                    status(s, date, raw.get(s.userId()), specials), byUser.get(s.userId()));
        });
    }

    /** One employee's month day by day, newest first, up to today. */
    @Transactional(readOnly = true)
    public PageResponse<DayRow> employeeDays(long userId, YearMonth month, int page, int size) {
        Shift shift = shiftOf(userId).orElseThrow(() -> ApiException.notFound("Employee", userId));
        List<AttendanceRecord> list = records.findByUserIdAndWorkDateBetween(userId, month.atDay(1), month.atEndOfMonth());
        Map<LocalDate, RecordView> views = toViews(list).stream().collect(Collectors.toMap(RecordView::date, r -> r));
        Map<LocalDate, AttendanceRecord> raw = list.stream().collect(Collectors.toMap(AttendanceRecord::getWorkDate, r -> r));
        LocalDate last = month.atEndOfMonth().isAfter(today()) ? today() : month.atEndOfMonth();
        List<LocalDate> dates = new ArrayList<>();
        List<Special> specials = specials();
        for (LocalDate d = last; !d.isBefore(month.atDay(1)); d = d.minusDays(1)) {
            if (!"NONE".equals(status(shift, d, raw.get(d), specials))) {
                dates.add(d);
            }
        }
        return slice(dates, page, size, d -> {
            RecordView view = views.get(d);
            Day day = dayOf(shift, d, specials);
            return new DayRow(userId, shift.fullName(), d, view == null ? day.checkIn() : view.shiftStart(),
                    view == null ? day.checkOut() : view.shiftEnd(), status(shift, d, raw.get(d), specials), view);
        });
    }

    /** A page of the monthly sheet. */
    @Transactional(readOnly = true)
    public MonthSheet month(YearMonth month, Long userId, int page, int size) {
        List<Shift> employees = employees(userId);
        Map<Long, List<AttendanceRecord>> byUser = monthRecords(month);
        List<Special> specials = specials();
        PageResponse<MonthRow> rows = slice(employees, page, size,
                s -> monthRow(s, month, byUser.getOrDefault(s.userId(), List.of()), specials));
        return new MonthSheet(month.toString(), dates(month), rows.content(), rows.page(), rows.size(),
                rows.totalElements(), rows.totalPages());
    }

    /** The whole monthly sheet, for Excel. */
    @Transactional(readOnly = true)
    public MonthSheet monthForExport(YearMonth month, Long userId) {
        List<Shift> employees = employees(userId);
        return month(month, userId, 0, Math.max(employees.size(), 1));
    }

    /** Days waiting for the admin, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<RecordView> pending(Long userId, int page, int size) {
        PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Order.desc("workDate"), Sort.Order.desc("id")));
        Page<AttendanceRecord> found = userId == null
                ? records.findByReviewStatus(AttendanceRecord.Review.PENDING, request)
                : records.findByReviewStatusAndUserId(AttendanceRecord.Review.PENDING, userId, request);
        return new PageResponse<>(toViews(found.getContent()), found.getNumber(), found.getSize(),
                found.getTotalElements(), found.getTotalPages());
    }

    /** Approves a day, with a late fee if given. Also corrects the fee of a day already approved. */
    @Transactional
    public RecordView review(long id, ReviewRequest request, long adminId) {
        AttendanceRecord record = records.findById(id).orElseThrow(() -> ApiException.notFound("Attendance record", id));
        requireNotOwn(record.getUserId(), adminId);
        if (record.getReviewStatus() == AttendanceRecord.Review.NONE) {
            throw ApiException.conflict("NOTHING_TO_REVIEW", "This day has nothing to review");
        }
        record.review(request.lateFee() == null ? BigDecimal.ZERO : request.lateFee(), blankToNull(request.note()),
                adminId, clock.instant());
        return toViews(List.of(record)).getFirst();
    }

    /** Sets a day's times by hand, or clears the day when both are empty. */
    @Transactional
    public void correct(DayCorrection request, long adminId) {
        requireNotOwn(request.userId(), adminId);
        Shift shift = shiftOf(request.userId())
                .orElseThrow(() -> ApiException.badRequest("NO_SHIFT", "Set this employee's shift first"));
        Optional<AttendanceRecord> existing = records.findByUserIdAndWorkDate(request.userId(), request.date());
        if (request.checkIn() == null && request.checkOut() != null) {
            throw ApiException.badRequest("CHECK_IN_REQUIRED", "Enter the check-in time too");
        }
        if (request.checkIn() != null && request.checkOut() != null && !request.checkOut().isAfter(request.checkIn())) {
            throw ApiException.badRequest("INVALID_TIMES", "Check-out must be after check-in");
        }
        if (request.checkIn() == null) {
            existing.ifPresent(records::delete);
            return;
        }
        Day day = dayOf(shift, request.date(), specials());
        AttendanceRecord record = existing.orElseGet(() ->
                new AttendanceRecord(request.userId(), request.date(), day.checkIn(), day.checkOut()));
        LocalTime allowed = record.getShiftStart().plusMinutes(shift.graceMinutes());
        int late = !day.off() && request.checkIn().isAfter(allowed)
                ? minutes(record.getShiftStart(), request.checkIn()) : 0;
        int early = !day.off() && request.checkOut() != null && request.checkOut().isBefore(record.getShiftEnd())
                ? minutes(request.checkOut(), record.getShiftEnd()) : 0;
        record.correct(instant(request.date(), request.checkIn()),
                request.checkOut() == null ? null : instant(request.date(), request.checkOut()),
                late, early, request.note().trim(), adminId, clock.instant());
        records.save(record);
    }

    // ---- Settings ----

    /** Every active non-admin, with their shift if they have one. */
    @Transactional(readOnly = true)
    public List<ShiftView> shifts() {
        return jdbc.sql("""
                        select u.id, u.full_name, s.check_in_time, s.check_out_time, coalesce(s.grace_minutes, 0),
                               coalesce(s.weekly_off, ''),
                               exists(select 1 from user_permissions p where p.user_id = u.id and p.permission = 'ATTENDANCE_MARK')
                        from users u left join attendance_shifts s on s.user_id = u.id
                        where u.active and u.role <> 'ADMIN'
                        order by lower(u.full_name)
                        """)
                .query((rs, i) -> new ShiftView(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalTime.class),
                        rs.getObject(4, LocalTime.class), rs.getInt(5), parseDays(rs.getString(6)), rs.getBoolean(7)))
                .list();
    }

    /** Sets someone's hours and days off. Whether they can check in is the attendance permission's business. */
    @Transactional
    public void setShift(long userId, ShiftRequest request) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Employee", userId));
        if (user.getRole() == Role.ADMIN) {
            throw ApiException.badRequest("ADMIN_NO_ATTENDANCE", "Admins don't give attendance");
        }
        if (!request.checkOut().isAfter(request.checkIn())) {
            throw ApiException.badRequest("INVALID_TIMES", "Check-out must be after check-in");
        }
        jdbc.sql("""
                        insert into attendance_shifts (user_id, check_in_time, check_out_time, grace_minutes, weekly_off, starts_on, updated_at)
                        values (:user, :in, :out, :grace, :off, :today, :now)
                        on conflict (user_id) do update set check_in_time = excluded.check_in_time,
                            check_out_time = excluded.check_out_time, grace_minutes = excluded.grace_minutes,
                            weekly_off = excluded.weekly_off, updated_at = excluded.updated_at
                        """)
                .param("user", userId).param("in", request.checkIn()).param("out", request.checkOut())
                .param("grace", request.graceMinutes())
                .param("off", request.weeklyOff().stream().map(DayOfWeek::name).sorted().collect(Collectors.joining(",")))
                .param("today", Date.valueOf(today())).param("now", Timestamp.from(clock.instant()))
                .update();
    }

    @Transactional
    public void removeShift(long userId) {
        jdbc.sql("delete from attendance_shifts where user_id = :id").param("id", userId).update();
    }

    @Transactional(readOnly = true)
    public List<SpecialHoursView> specialHours() {
        return specials().stream().map(s -> new SpecialHoursView(s.id(), s.userId(), s.personName(), s.weekDay(), s.from(), s.to(),
                s.off(), s.checkIn(), s.checkOut(), s.note())).toList();
    }

    @Transactional
    public void addSpecialHours(SpecialHoursRequest request) {
        requireValid(request);
        specialParams(jdbc.sql("""
                        insert into attendance_special_hours (user_id, week_day, date_from, date_to, day_off, check_in_time, check_out_time, note, created_at, updated_at)
                        values (:user, :day, :from, :to, :off, :in, :out, :note, :now, :now)
                        """), request).update();
    }

    @Transactional
    public void updateSpecialHours(long id, SpecialHoursRequest request) {
        requireValid(request);
        int changed = specialParams(jdbc.sql("""
                        update attendance_special_hours set user_id = :user, week_day = :day, date_from = :from, date_to = :to,
                            day_off = :off, check_in_time = :in, check_out_time = :out, note = :note, updated_at = :now
                        where id = :id
                        """), request).param("id", id).update();
        if (changed == 0) {
            throw ApiException.notFound("Special hours", id);
        }
    }

    @Transactional
    public void deleteSpecialHours(long id) {
        jdbc.sql("delete from attendance_special_hours where id = :id").param("id", id).update();
    }

    private JdbcClient.StatementSpec specialParams(JdbcClient.StatementSpec sql, SpecialHoursRequest r) {
        LocalDate to = r.dateTo() == null ? r.dateFrom() : r.dateTo();
        return sql.param("user", r.userId()).param("day", r.weekDay() == null ? null : r.weekDay().name())
                .param("from", r.dateFrom() == null ? null : Date.valueOf(r.dateFrom())).param("to", to == null ? null : Date.valueOf(to))
                .param("off", r.dayOff()).param("in", r.dayOff() ? null : r.checkIn()).param("out", r.dayOff() ? null : r.checkOut())
                .param("note", blankToNull(r.note())).param("now", Timestamp.from(clock.instant()));
    }

    private void requireValid(SpecialHoursRequest r) {
        if ((r.weekDay() == null) == (r.dateFrom() == null)) {
            throw ApiException.badRequest("INVALID_SPECIAL_DAY", "Choose a date or a weekday");
        }
        if (r.dateTo() != null && r.dateFrom() != null && r.dateTo().isBefore(r.dateFrom())) {
            throw ApiException.badRequest("INVALID_SPECIAL_DAY", "The end date can't be before the start date");
        }
        if (!r.dayOff() && (r.checkIn() == null || r.checkOut() == null)) {
            throw ApiException.badRequest("INVALID_TIMES", "Enter the check-in and check-out times");
        }
        if (!r.dayOff() && !r.checkOut().isAfter(r.checkIn())) {
            throw ApiException.badRequest("INVALID_TIMES", "Check-out must be after check-in");
        }
        if (r.userId() != null) {
            User user = users.findById(r.userId()).orElseThrow(() -> ApiException.notFound("Employee", r.userId()));
            if (user.getRole() == Role.ADMIN) {
                throw ApiException.badRequest("ADMIN_NO_ATTENDANCE", "Admins don't give attendance");
            }
        }
    }

    @Transactional(readOnly = true)
    public List<LocationView> locations() {
        return jdbc.sql("""
                        select l.id, l.name, l.latitude, l.longitude, l.radius_m, l.active,
                               exists(select 1 from attendance_records r
                                      where r.check_in_location_id = l.id or r.check_out_location_id = l.id)
                        from work_locations l order by l.id
                        """)
                .query((rs, i) -> new LocationView(rs.getLong(1), rs.getString(2), rs.getObject(3, Double.class),
                        rs.getObject(4, Double.class), rs.getInt(5), rs.getBoolean(6), rs.getBoolean(7)))
                .list();
    }

    @Transactional
    public void createLocation(LocationRequest request) {
        requireBothOrNeither(request);
        String name = request.name().trim();
        requireFreeName(name, 0);
        jdbc.sql("""
                        insert into work_locations (name, latitude, longitude, radius_m, active, created_at, updated_at)
                        values (:name, :lat, :lng, :radius, :active, :now, :now)
                        """)
                .param("name", name).param("lat", request.latitude()).param("lng", request.longitude())
                .param("radius", request.radiusM()).param("active", request.active() == null || request.active())
                .param("now", Timestamp.from(clock.instant())).update();
    }

    @Transactional
    public void updateLocation(long id, LocationRequest request) {
        requireBothOrNeither(request);
        String name = request.name().trim();
        requireFreeName(name, id);
        int changed = jdbc.sql("""
                        update work_locations set name = :name, latitude = :lat, longitude = :lng, radius_m = :radius,
                            active = :active, updated_at = :now where id = :id
                        """)
                .param("name", name).param("lat", request.latitude()).param("lng", request.longitude())
                .param("radius", request.radiusM()).param("active", request.active() == null || request.active())
                .param("now", Timestamp.from(clock.instant())).param("id", id).update();
        if (changed == 0) {
            throw ApiException.notFound("Location", id);
        }
    }

    /** Only an unused location can be deleted; a used one is switched off so past days keep its name. */
    @Transactional
    public void deleteLocation(long id) {
        boolean used = locations().stream().anyMatch(l -> l.id() == id && l.used());
        if (used) {
            throw ApiException.conflict("LOCATION_IN_USE", "Attendance was given at this location. Switch it off instead.");
        }
        jdbc.sql("delete from work_locations where id = :id").param("id", id).update();
    }

    // ---- Rules ----

    /** The day's status for an employee, given their record (if any). */
    private String status(Shift shift, LocalDate date, AttendanceRecord record, List<Special> specials) {
        if (record != null && record.getCheckInAt() != null) {
            boolean late = record.getLateMinutes() > 0;
            boolean early = record.getEarlyMinutes() > 0;
            if (record.getCheckOutAt() == null && date.isBefore(today())) {
                return "NO_CHECK_OUT";
            }
            return late && early ? "LATE_EARLY" : late ? "LATE" : early ? "EARLY" : "PRESENT";
        }
        if (date.isBefore(shift.startsOn())) {
            return "NONE";
        }
        Day day = dayOf(shift, date, specials);
        if (day.off()) {
            return "OFF";
        }
        LocalDate today = today();
        if (date.isAfter(today) || (date.equals(today) && !nowTime().isAfter(day.checkOut()))) {
            return "WAITING";
        }
        return "ABSENT";
    }

    private MonthRow monthRow(Shift shift, YearMonth month, List<AttendanceRecord> list, List<Special> specials) {
        Map<LocalDate, AttendanceRecord> byDate = list.stream()
                .collect(Collectors.toMap(AttendanceRecord::getWorkDate, r -> r));
        List<DayCell> cells = new ArrayList<>();
        int present = 0;
        int late = 0;
        int early = 0;
        int absent = 0;
        int off = 0;
        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
            AttendanceRecord r = byDate.get(d);
            String status = status(shift, d, r, specials);
            switch (status) {
                case "ABSENT" -> absent++;
                case "OFF" -> off++;
                case "NONE", "WAITING" -> { }
                default -> present++;
            }
            if (r != null) {
                late += r.getLateMinutes() > 0 ? 1 : 0;
                early += r.getEarlyMinutes() > 0 ? 1 : 0;
            }
            cells.add(new DayCell(d, status, r == null ? 0 : r.getLateMinutes(), r == null ? 0 : r.getEarlyMinutes(),
                    r == null ? BigDecimal.ZERO : r.getLateFee(), r == null ? null : r.getCheckInAt(),
                    r == null ? null : r.getCheckOutAt()));
        }
        return new MonthRow(shift.userId(), shift.fullName(), cells, present, late, early, absent, off, lateFees(list),
                workedMinutes(list));
    }

    private Map<Long, List<AttendanceRecord>> monthRecords(YearMonth month) {
        return records.findByWorkDateBetween(month.atDay(1), month.atEndOfMonth()).stream()
                .collect(Collectors.groupingBy(AttendanceRecord::getUserId));
    }

    private static List<LocalDate> dates(YearMonth month) {
        return month.atDay(1).datesUntil(month.atEndOfMonth().plusDays(1)).toList();
    }

    private static long workedMinutes(List<AttendanceRecord> list) {
        return list.stream().map(AttendanceService::workedMinutes).filter(m -> m != null).mapToLong(Integer::longValue).sum();
    }

    /** Null until the day has both a check-in and a check-out: hours are never guessed. */
    private static Integer workedMinutes(AttendanceRecord r) {
        return r.getCheckInAt() == null || r.getCheckOutAt() == null ? null
                : (int) Duration.between(r.getCheckInAt(), r.getCheckOutAt()).toMinutes();
    }

    private static BigDecimal lateFees(List<AttendanceRecord> list) {
        return list.stream().map(AttendanceRecord::getLateFee).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Pages a short list held in memory (the employees of one shop, the days of one month). */
    private static <E, T> PageResponse<T> slice(List<E> all, int page, int size, Function<E, T> mapper) {
        int from = (int) Math.min((long) page * size, all.size());
        int to = (int) Math.min((long) from + size, all.size());
        return new PageResponse<>(all.subList(from, to).stream().map(mapper).toList(), page, size, all.size(),
                (int) Math.ceil((double) all.size() / size));
    }

    /** Works out the nearest location with a position, and whether the phone is inside its radius. */
    private AttendanceRecord.Place place(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return new AttendanceRecord.Place(null, null, null, null, false);
        }
        record Distance(Location location, int metres) {
        }
        return allLocations().stream()
                .filter(l -> l.active() && l.latitude() != null && l.longitude() != null)
                .map(l -> new Distance(l, (int) Math.round(metres(latitude, longitude, l.latitude(), l.longitude()))))
                .min(Comparator.comparingInt(Distance::metres))
                .map(d -> new AttendanceRecord.Place(latitude, longitude, d.location().id(), d.metres(),
                        d.metres() <= d.location().radiusM()))
                .orElseGet(() -> new AttendanceRecord.Place(latitude, longitude, null, null, false));
    }

    /** Distance between two points on Earth (haversine). */
    static double metres(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static ApiException reasonRequired(String timing, AttendanceRecord.Place place) {
        String where = place.inRange() ? null : place.latitude() == null
                ? "Your location could not be read."
                : "You are not at any work location.";
        String message = (timing == null ? "" : timing + " ") + (where == null ? "" : where + " ") + "Please give a reason.";
        return ApiException.badRequest("REASON_REQUIRED", message);
    }

    private Shift requireEmployee(long userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Employee", userId));
        if (user.getRole() == Role.ADMIN) {
            throw ApiException.conflict("ADMIN_NO_ATTENDANCE", "Admins don't give attendance");
        }
        return shiftOf(userId).orElseThrow(() ->
                ApiException.conflict("NO_SHIFT", "Your working hours haven't been set yet. Ask an admin."));
    }

    /** Nobody approves or corrects their own attendance. */
    private static void requireNotOwn(long employeeId, long actorId) {
        if (employeeId == actorId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWN_ATTENDANCE", "You can't change your own attendance");
        }
    }

    private void requireBothOrNeither(LocationRequest request) {
        if ((request.latitude() == null) != (request.longitude() == null)) {
            throw ApiException.badRequest("INVALID_POSITION", "Enter both latitude and longitude");
        }
    }

    private void requireFreeName(String name, long excludeId) {
        boolean taken = allLocations().stream().anyMatch(l -> l.id() != excludeId && l.name().equalsIgnoreCase(name));
        if (taken) {
            throw ApiException.conflict("LOCATION_EXISTS", "A location named " + name + " already exists");
        }
    }

    /**
     * What applies on a day, from the most specific to the least: dates, the weekly day off, a weekday, the normal shift.
     * Dates beat a day off, so someone can be called in on one. Someone's own setting beats one for everyone.
     */
    private static Day dayOf(Shift shift, LocalDate date, List<Special> specials) {
        Optional<Special> dated = pick(specials, shift, s -> s.from() != null && !date.isBefore(s.from()) && !date.isAfter(s.to()));
        if (dated.isPresent()) {
            return toDay(dated.get(), shift);
        }
        if (shift.weeklyOff().contains(date.getDayOfWeek())) {
            return new Day(shift.checkIn(), shift.checkOut(), true);
        }
        return pick(specials, shift, s -> s.weekDay() == date.getDayOfWeek()).map(s -> toDay(s, shift))
                .orElse(new Day(shift.checkIn(), shift.checkOut(), false));
    }

    private static Optional<Special> pick(List<Special> specials, Shift shift, Predicate<Special> applies) {
        List<Special> matching = specials.stream().filter(applies).toList();
        return matching.stream().filter(s -> s.userId() != null && s.userId() == shift.userId()).findFirst()
                .or(() -> matching.stream().filter(s -> s.userId() == null).findFirst());
    }

    private static Day toDay(Special special, Shift shift) {
        return special.off() ? new Day(shift.checkIn(), shift.checkOut(), true) : new Day(special.checkIn(), special.checkOut(), false);
    }

    private List<Special> specials() {
        return jdbc.sql("""
                        select h.id, h.user_id, u.full_name, h.week_day, h.date_from, h.date_to, h.day_off, h.check_in_time, h.check_out_time, h.note
                        from attendance_special_hours h left join users u on u.id = h.user_id
                        order by h.date_from desc nulls last, h.id desc
                        """)
                .query((rs, i) -> new Special(rs.getLong(1), rs.getObject(2, Long.class), rs.getString(3),
                        rs.getString(4) == null ? null : DayOfWeek.valueOf(rs.getString(4)), rs.getObject(5, LocalDate.class),
                        rs.getObject(6, LocalDate.class), rs.getBoolean(7), rs.getObject(8, LocalTime.class),
                        rs.getObject(9, LocalTime.class), rs.getString(10)))
                .list();
    }

    private static Set<DayOfWeek> parseDays(String days) {
        return days == null || days.isBlank() ? Set.of()
                : Arrays.stream(days.split(",")).map(DayOfWeek::valueOf).collect(Collectors.toCollection(TreeSet::new));
    }

    private static int minutes(LocalTime from, LocalTime to) {
        return (int) Duration.between(from, to).toMinutes();
    }

    private Instant instant(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(appProperties.zoneId()).toInstant();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(appProperties.zoneId()));
    }

    private LocalTime nowTime() {
        return LocalTime.now(clock.withZone(appProperties.zoneId())).truncatedTo(ChronoUnit.MINUTES);
    }

    private static ShiftView toView(Shift s) {
        return new ShiftView(s.userId(), s.fullName(), s.checkIn(), s.checkOut(), s.graceMinutes(), s.weeklyOff(), true);
    }

    private Optional<Shift> shiftOf(long userId) {
        return shiftRows("where u.id = :id", Map.of("id", userId)).stream().findFirst();
    }

    private List<Shift> employees() {
        return shiftRows("where u.active and u.role <> 'ADMIN'", Map.of());
    }

    private List<Shift> employees(Long userId) {
        return employees().stream().filter(s -> userId == null || s.userId() == userId).toList();
    }

    private List<Shift> shiftRows(String where, Map<String, Object> params) {
        return jdbc.sql("""
                        select u.id, u.full_name, s.check_in_time, s.check_out_time, s.grace_minutes, s.weekly_off, s.starts_on
                        from attendance_shifts s join users u on u.id = s.user_id
                        """ + where + " order by lower(u.full_name)")
                .params(params)
                .query((rs, i) -> new Shift(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalTime.class),
                        rs.getObject(4, LocalTime.class), rs.getInt(5), parseDays(rs.getString(6)), rs.getObject(7, LocalDate.class)))
                .list();
    }

    private List<Location> allLocations() {
        return jdbc.sql("select id, name, latitude, longitude, radius_m, active from work_locations")
                .query((rs, i) -> new Location(rs.getLong(1), rs.getString(2), rs.getObject(3, Double.class),
                        rs.getObject(4, Double.class), rs.getInt(5), rs.getBoolean(6)))
                .list();
    }

    private List<RecordView> toViews(List<AttendanceRecord> list) {
        Map<Long, String> locationNames = new HashMap<>();
        allLocations().forEach(l -> locationNames.put(l.id(), l.name()));
        List<Long> people = new ArrayList<>();
        list.forEach(r -> {
            people.add(r.getUserId());
            if (r.getReviewedBy() != null) {
                people.add(r.getReviewedBy());
            }
        });
        Map<Long, String> names = userDirectory.namesById(people);
        return list.stream().map(r -> new RecordView(r.getId(), r.getUserId(), UserDirectory.nameOf(names, r.getUserId()),
                r.getWorkDate(), r.getShiftStart(), r.getShiftEnd(), r.getCheckInAt(),
                locationNames.get(r.getCheckInLocationId()), r.getCheckInDistanceM(), r.isCheckInInRange(),
                r.getCheckInLatitude(), r.getCheckInLongitude(),
                r.getCheckInPhoto() == null ? null : files.url(r.getCheckInPhoto()), r.getCheckInReason(),
                r.getLateMinutes(), r.getCheckOutAt(), locationNames.get(r.getCheckOutLocationId()),
                r.getCheckOutDistanceM(), r.isCheckOutInRange(), r.getCheckOutLatitude(), r.getCheckOutLongitude(),
                r.getCheckOutReason(), r.getEarlyMinutes(), r.getReviewStatus().name(), r.getLateFee(),
                r.getReviewedBy() == null ? null : UserDirectory.nameOf(names, r.getReviewedBy()), r.getReviewedAt(),
                r.getReviewNote(), r.getEditNote(), workedMinutes(r)))
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
