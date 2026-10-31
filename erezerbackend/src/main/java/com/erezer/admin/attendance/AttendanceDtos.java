package com.erezer.admin.attendance;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

public final class AttendanceDtos {

    private AttendanceDtos() {
    }

    // ---- Requests ----

    /** @param latitude null when the phone couldn't give a position (the day then goes to the admin) */
    public record CheckInRequest(Double latitude, Double longitude, @NotNull Long photoFileId,
            @Size(max = 500) String reason) {
    }

    public record CheckOutRequest(Double latitude, Double longitude, @Size(max = 500) String reason) {
    }

    /** @param lateFee 0 approves without a fee */
    public record ReviewRequest(
            @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal lateFee,
            @Size(max = 500) String note) {
    }

    /** A correction by hand. Both times empty clears the day (absent). */
    public record DayCorrection(@NotNull Long userId, @NotNull LocalDate date, LocalTime checkIn, LocalTime checkOut,
            @NotBlank @Size(max = 500) String note) {
    }

    public record ShiftRequest(
            @NotNull LocalTime checkIn,
            @NotNull LocalTime checkOut,
            @Min(0) @Max(240) int graceMinutes,
            @NotNull @Size(max = 6) Set<DayOfWeek> weeklyOff) {
    }

    /**
     * Hours that differ from the normal shift.
     *
     * @param userId null means everyone
     * @param weekDay a weekday, or null for {@code dateFrom} to {@code dateTo}
     */
    public record SpecialHoursRequest(Long userId, DayOfWeek weekDay, LocalDate dateFrom, LocalDate dateTo, boolean dayOff,
            LocalTime checkIn, LocalTime checkOut, @Size(max = 120) String note) {
    }

    public record SpecialHoursView(long id, Long userId, String personName, DayOfWeek weekDay, LocalDate dateFrom,
            LocalDate dateTo, boolean dayOff, LocalTime checkIn, LocalTime checkOut, String note) {
    }

    public record LocationRequest(
            @NotBlank @Size(max = 80) String name,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @Min(10) @Max(5000) int radiusM,
            Boolean active) {
    }

    // ---- Responses ----

    public record RecordView(
            long id,
            long userId,
            String fullName,
            LocalDate date,
            LocalTime shiftStart,
            LocalTime shiftEnd,
            Instant checkInAt,
            String checkInLocation,
            Integer checkInDistanceM,
            boolean checkInInRange,
            Double checkInLatitude,
            Double checkInLongitude,
            String photoUrl,
            String checkInReason,
            int lateMinutes,
            Instant checkOutAt,
            String checkOutLocation,
            Integer checkOutDistanceM,
            boolean checkOutInRange,
            Double checkOutLatitude,
            Double checkOutLongitude,
            String checkOutReason,
            int earlyMinutes,
            String reviewStatus,
            BigDecimal lateFee,
            String reviewedBy,
            Instant reviewedAt,
            String reviewNote,
            String editNote,
            Integer workedMinutes) {
    }

    /**
     * An employee's shift; times are null while none is set.
     *
     * @param canCheckIn they hold the attendance permission, which only an admin decides
     */
    public record ShiftView(long userId, String fullName, LocalTime checkIn, LocalTime checkOut, int graceMinutes,
            Set<DayOfWeek> weeklyOff, boolean canCheckIn) {
    }

    /** What an employee's own page shows today. */
    public record MyToday(LocalDate date, ShiftView shift, boolean weeklyOff, RecordView record, LocalTime todayCheckIn,
            LocalTime todayCheckOut) {
    }

    public record LocationView(long id, String name, Double latitude, Double longitude, int radiusM, boolean active,
            boolean used) {
    }

    /** One employee on one day. */
    public record DayRow(long userId, String fullName, LocalDate date, LocalTime shiftStart, LocalTime shiftEnd,
            String status, RecordView record) {
    }

    public record DayCell(LocalDate date, String status, int lateMinutes, int earlyMinutes, BigDecimal lateFee,
            Instant checkInAt, Instant checkOutAt) {
    }

    public record MonthRow(long userId, String fullName, List<DayCell> days, int present, int late, int early,
            int absent, int off, BigDecimal lateFees, long workedMinutes) {
    }

    public record MonthSheet(String month, List<LocalDate> dates, List<MonthRow> rows, int page, int size,
            long totalElements, int totalPages) {
    }

    /** Someone who can be picked in the employee filter. */
    public record EmployeeOption(long userId, String fullName) {
    }

    /** The cards above the lists. Only days with both a check-in and a check-out count as hours worked. */
    public record Summary(long workedMinutes, BigDecimal lateFees) {
    }
}
