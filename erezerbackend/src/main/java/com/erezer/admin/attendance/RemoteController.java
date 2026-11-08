package com.erezer.admin.attendance;

import com.erezer.admin.attendance.RemoteDtos.DayView;
import com.erezer.admin.attendance.RemoteDtos.Me;
import com.erezer.admin.attendance.RemoteDtos.Mode;
import com.erezer.admin.attendance.RemoteDtos.ModeRequest;
import com.erezer.admin.attendance.RemoteDtos.MyMonth;
import com.erezer.admin.attendance.RemoteDtos.OffRequest;
import com.erezer.admin.attendance.RemoteDtos.OnRequest;
import com.erezer.admin.attendance.RemoteDtos.PersonCard;
import com.erezer.admin.attendance.RemoteDtos.ReviewRequest;
import com.erezer.admin.attendance.RemoteDtos.SessionRequest;
import com.erezer.admin.attendance.RemoteDtos.Summary;
import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.notification.NotificationAction;
import com.erezer.admin.notification.Notifies;
import com.erezer.admin.security.CurrentUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RemoteController {

    private static final int MAX_PAGE_SIZE = 100;

    private final RemoteService remote;
    private final AttendanceExportService export;

    RemoteController(RemoteService remote, AttendanceExportService export) {
        this.remote = remote;
        this.export = export;
    }

    /** Whether the signed-in person works remotely, so My Attendance shows the right buttons. */
    @GetMapping("/api/attendance/my-mode")
    Mode myMode() {
        return new Mode(remote.isRemote(CurrentUser.id()));
    }

    @Notifies(NotificationAction.ATTENDANCE_TYPE_CHANGED)
    @PutMapping("/api/attendance/mode/{userId}")
    @PreAuthorize("hasAuthority('ATTENDANCE_SETTINGS')")
    ResponseEntity<Void> setMode(@PathVariable long userId, @RequestBody ModeRequest request) {
        remote.setRemote(userId, request.remote());
        Notifies.Context.detail(request.remote() ? "Now works remotely" : "Now works at the office");
        return ResponseEntity.noContent().build();
    }

    // ---- The person's own ----

    @GetMapping("/api/remote/me")
    @PreAuthorize("hasAuthority('REMOTE_MARK')")
    Me me() {
        return remote.me(CurrentUser.id());
    }

    @GetMapping("/api/remote/me/month")
    @PreAuthorize("hasAuthority('REMOTE_MARK')")
    MyMonth myMonth(@RequestParam YearMonth month) {
        return remote.myMonth(CurrentUser.id(), month);
    }

    @Notifies(NotificationAction.REMOTE_ON)
    @PostMapping("/api/remote/on")
    @PreAuthorize("hasAuthority('REMOTE_MARK')")
    Me on(@Valid @RequestBody OnRequest request) {
        Me me = remote.on(CurrentUser.id(), request);
        if (me.day() != null) {
            Notifies.Context.target(me.day().id());
            if (me.day().lateMinutes() > 0 && me.day().sessions().size() == 1) {
                Notifies.Context.as(NotificationAction.REMOTE_LATE);
                Notifies.Context.detail(me.day().lateReason());
            }
        }
        return me;
    }

    @Notifies(NotificationAction.REMOTE_OFF)
    @PostMapping("/api/remote/off")
    @PreAuthorize("hasAuthority('REMOTE_MARK')")
    Me off(@Valid @RequestBody OffRequest request) {
        Me me = remote.off(CurrentUser.id(), request);
        if (me.day() != null) {
            Notifies.Context.target(me.day().id());
            if (request.done() && me.day().earlyMinutes() > 0) {
                Notifies.Context.as(NotificationAction.REMOTE_DONE_EARLY);
                Notifies.Context.detail(me.day().earlyReason());
            }
        }
        return me;
    }

    // ---- Everyone's ----

    @GetMapping("/api/remote/summary")
    @PreAuthorize("hasAuthority('REMOTE_VIEW')")
    Summary summary(@RequestParam YearMonth month, @RequestParam(required = false) Long userId) {
        return remote.summary(month, userId);
    }

    @GetMapping("/api/remote/people")
    @PreAuthorize("hasAuthority('REMOTE_VIEW')")
    PageResponse<PersonCard> people(@RequestParam YearMonth month, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return remote.people(month, Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    /** Everyone on {@code date}, or with {@code userId} that person's {@code month}. */
    @GetMapping("/api/remote/days")
    @PreAuthorize("hasAuthority('REMOTE_VIEW')")
    PageResponse<DayView> days(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) YearMonth month,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return remote.days(date, month, userId, Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/api/remote/pending")
    @PreAuthorize("hasAuthority('REMOTE_APPROVE')")
    PageResponse<DayView> pending(@RequestParam(required = false) Long userId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return remote.pending(userId, Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @Notifies(NotificationAction.REMOTE_REVIEWED)
    @PostMapping("/api/remote/days/{id}/review")
    @PreAuthorize("hasAuthority('REMOTE_APPROVE')")
    DayView review(@PathVariable long id, @Valid @RequestBody ReviewRequest request) {
        return remote.review(id, request.lateFee(), request.note(), CurrentUser.id());
    }

    @Notifies(NotificationAction.REMOTE_DAY_DELETED)
    @DeleteMapping("/api/remote/days/{id}")
    @PreAuthorize("hasAuthority('REMOTE_EDIT')")
    ResponseEntity<Void> deleteDay(@PathVariable long id) {
        remote.deleteDay(id, CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    @Notifies(NotificationAction.REMOTE_SESSION_ADDED)
    @PostMapping("/api/remote/sessions")
    @PreAuthorize("hasAuthority('REMOTE_EDIT')")
    DayView addSession(@Valid @RequestBody SessionRequest request) {
        DayView day = remote.addSession(request, CurrentUser.id());
        Notifies.Context.target(day.id());
        Notifies.Context.detail(request.note());
        return day;
    }

    @Notifies(NotificationAction.REMOTE_SESSION_EDITED)
    @PutMapping("/api/remote/sessions/{id}")
    @PreAuthorize("hasAuthority('REMOTE_EDIT')")
    DayView updateSession(@PathVariable long id, @Valid @RequestBody SessionRequest request) {
        DayView day = remote.updateSession(id, request, CurrentUser.id());
        Notifies.Context.target(day.id());
        Notifies.Context.detail(request.note());
        return day;
    }

    @Notifies(NotificationAction.REMOTE_SESSION_DELETED)
    @DeleteMapping("/api/remote/sessions/{id}")
    @PreAuthorize("hasAuthority('REMOTE_EDIT')")
    ResponseEntity<Void> deleteSession(@PathVariable long id) {
        remote.deleteSession(id, CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/remote/month/export.xlsx")
    @PreAuthorize("hasAuthority('REMOTE_VIEW')")
    ResponseEntity<byte[]> exportMonth(@RequestParam YearMonth month) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("remote-attendance-" + month + ".xlsx").build().toString())
                .body(export.exportRemote(month.toString(), remote.exportRows(month)));
    }
}
