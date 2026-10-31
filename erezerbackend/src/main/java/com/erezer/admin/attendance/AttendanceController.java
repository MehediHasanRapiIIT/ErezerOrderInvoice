package com.erezer.admin.attendance;

import com.erezer.admin.attendance.AttendanceDtos.CheckInRequest;
import com.erezer.admin.attendance.AttendanceDtos.CheckOutRequest;
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
import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.notification.NotificationAction;
import com.erezer.admin.notification.Notifies;
import com.erezer.admin.security.CurrentUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/attendance")
class AttendanceController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AttendanceService attendance;
    private final AttendanceExportService export;

    AttendanceController(AttendanceService attendance, AttendanceExportService export) {
        this.attendance = attendance;
        this.export = export;
    }

    // ---- The employee's own attendance ----

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('ATTENDANCE_MARK')")
    MyToday today() {
        return attendance.today(CurrentUser.id());
    }

    @GetMapping("/me/month")
    @PreAuthorize("hasAuthority('ATTENDANCE_MARK')")
    MonthRow myMonth(@RequestParam YearMonth month) {
        return attendance.myMonth(CurrentUser.id(), month);
    }

    @Notifies(NotificationAction.ATTENDANCE_CHECK_IN)
    @PostMapping("/check-in")
    @PreAuthorize("hasAuthority('ATTENDANCE_MARK')")
    RecordView checkIn(@Valid @RequestBody CheckInRequest request) {
        RecordView record = attendance.checkIn(CurrentUser.id(), request);
        if (record.lateMinutes() > 0 || !record.checkInInRange()) {
            Notifies.Context.as(NotificationAction.ATTENDANCE_CHECK_IN_PROBLEM);
            Notifies.Context.detail(record.checkInReason());
        }
        return record;
    }

    @Notifies(NotificationAction.ATTENDANCE_CHECK_OUT)
    @PostMapping("/check-out")
    @PreAuthorize("hasAuthority('ATTENDANCE_MARK')")
    RecordView checkOut(@Valid @RequestBody CheckOutRequest request) {
        RecordView record = attendance.checkOut(CurrentUser.id(), request);
        if (record.earlyMinutes() > 0 || !record.checkOutInRange()) {
            Notifies.Context.as(NotificationAction.ATTENDANCE_CHECK_OUT_PROBLEM);
            Notifies.Context.detail(record.checkOutReason());
        }
        return record;
    }

    // ---- Everyone's attendance ----

    @GetMapping("/employees")
    @PreAuthorize("hasAuthority('ATTENDANCE_VIEW')")
    List<EmployeeOption> employees() {
        return attendance.employeeOptions();
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('ATTENDANCE_VIEW')")
    Summary summary(@RequestParam YearMonth month, @RequestParam(required = false) Long userId) {
        return attendance.summary(month, userId);
    }

    @GetMapping("/day")
    @PreAuthorize("hasAuthority('ATTENDANCE_VIEW')")
    PageResponse<DayRow> day(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return attendance.day(date, Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    /** One employee's month, day by day. */
    @GetMapping("/employees/{userId}/days")
    @PreAuthorize("hasAuthority('ATTENDANCE_VIEW')")
    PageResponse<DayRow> employeeDays(@PathVariable long userId, @RequestParam YearMonth month,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return attendance.employeeDays(userId, month, Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/month")
    @PreAuthorize("hasAuthority('ATTENDANCE_VIEW')")
    MonthSheet month(@RequestParam YearMonth month, @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return attendance.month(month, userId, Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/month/export.xlsx")
    @PreAuthorize("hasAuthority('ATTENDANCE_VIEW')")
    ResponseEntity<byte[]> exportMonth(@RequestParam YearMonth month, @RequestParam(required = false) Long userId) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("attendance-" + month + ".xlsx").build().toString())
                .body(export.export(attendance.monthForExport(month, userId)));
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAuthority('ATTENDANCE_APPROVE')")
    PageResponse<RecordView> pending(@RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return attendance.pending(userId, Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    /** Approve a late or early day, with a late fee if given. */
    @Notifies(NotificationAction.ATTENDANCE_REVIEWED)
    @PostMapping("/records/{id}/review")
    @PreAuthorize("hasAuthority('ATTENDANCE_APPROVE')")
    RecordView review(@PathVariable long id, @Valid @RequestBody ReviewRequest request) {
        return attendance.review(id, request, CurrentUser.id());
    }

    @Notifies(NotificationAction.ATTENDANCE_CORRECTED)
    @PutMapping("/day")
    @PreAuthorize("hasAuthority('ATTENDANCE_EDIT')")
    ResponseEntity<Void> correct(@Valid @RequestBody DayCorrection request) {
        attendance.correct(request, CurrentUser.id());
        Notifies.Context.detail(request.date() + " · " + request.note());
        return ResponseEntity.noContent().build();
    }

    // ---- Settings ----

    @GetMapping("/shifts")
    @PreAuthorize("hasAuthority('ATTENDANCE_SETTINGS')")
    List<ShiftView> shifts() {
        return attendance.shifts();
    }

    @Notifies(NotificationAction.ATTENDANCE_SHIFT_SET)
    @PutMapping("/shifts/{userId}")
    @PreAuthorize("hasAuthority('ATTENDANCE_SETTINGS')")
    ResponseEntity<Void> setShift(@PathVariable long userId, @Valid @RequestBody ShiftRequest request) {
        attendance.setShift(userId, request);
        return ResponseEntity.noContent().build();
    }

    @Notifies(NotificationAction.ATTENDANCE_SHIFT_REMOVED)
    @DeleteMapping("/shifts/{userId}")
    @PreAuthorize("hasAuthority('ATTENDANCE_SETTINGS')")
    ResponseEntity<Void> removeShift(@PathVariable long userId) {
        attendance.removeShift(userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/special-hours")
    @PreAuthorize("hasAuthority('ATTENDANCE_SETTINGS')")
    List<SpecialHoursView> specialHours() {
        return attendance.specialHours();
    }

    @Notifies(NotificationAction.ATTENDANCE_SPECIAL_HOURS_SET)
    @PostMapping("/special-hours")
    @PreAuthorize("hasAuthority('ATTENDANCE_SETTINGS')")
    ResponseEntity<Void> addSpecialHours(@Valid @RequestBody SpecialHoursRequest request) {
        attendance.addSpecialHours(request);
        Notifies.Context.detail(request.note());
        return ResponseEntity.noContent().build();
    }

    @Notifies(NotificationAction.ATTENDANCE_SPECIAL_HOURS_SET)
    @PutMapping("/special-hours/{id}")
    @PreAuthorize("hasAuthority('ATTENDANCE_SETTINGS')")
    ResponseEntity<Void> updateSpecialHours(@PathVariable long id, @Valid @RequestBody SpecialHoursRequest request) {
        attendance.updateSpecialHours(id, request);
        Notifies.Context.detail(request.note());
        return ResponseEntity.noContent().build();
    }

    @Notifies(NotificationAction.ATTENDANCE_SPECIAL_HOURS_DELETED)
    @DeleteMapping("/special-hours/{id}")
    @PreAuthorize("hasAuthority('ATTENDANCE_SETTINGS')")
    ResponseEntity<Void> deleteSpecialHours(@PathVariable long id) {
        attendance.deleteSpecialHours(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/locations")
    @PreAuthorize("hasAuthority('ATTENDANCE_SETTINGS')")
    List<LocationView> locations() {
        return attendance.locations();
    }

    @Notifies(NotificationAction.ATTENDANCE_LOCATION_CREATED)
    @PostMapping("/locations")
    @PreAuthorize("hasAuthority('ATTENDANCE_SETTINGS')")
    List<LocationView> createLocation(@Valid @RequestBody LocationRequest request) {
        attendance.createLocation(request);
        Notifies.Context.detail(request.name());
        return attendance.locations();
    }

    @Notifies(NotificationAction.ATTENDANCE_LOCATION_EDITED)
    @PutMapping("/locations/{id}")
    @PreAuthorize("hasAuthority('ATTENDANCE_SETTINGS')")
    List<LocationView> updateLocation(@PathVariable long id, @Valid @RequestBody LocationRequest request) {
        attendance.updateLocation(id, request);
        return attendance.locations();
    }

    @Notifies(NotificationAction.ATTENDANCE_LOCATION_DELETED)
    @DeleteMapping("/locations/{id}")
    @PreAuthorize("hasAuthority('ATTENDANCE_SETTINGS')")
    List<LocationView> deleteLocation(@PathVariable long id) {
        attendance.deleteLocation(id);
        return attendance.locations();
    }
}
