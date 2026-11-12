package com.erezer.admin.attendance;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class RemoteDtos {

    private RemoteDtos() {
    }

    // ---- Requests ----

    public record OnRequest(Long imageFileId, @Size(max = 300) String note, @Size(max = 500) String reason) {
    }

    /** @param done "Done for today"; false is a break, which never asks for a reason */
    public record OffRequest(boolean done, @Size(max = 300) String note, @Size(max = 500) String reason) {
    }

    public record ReviewRequest(
            @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal lateFee,
            @Size(max = 500) String note) {
    }

    /** A session by hand. {@code off} may be empty while it is still running today. */
    public record SessionRequest(@NotNull Long userId, @NotNull LocalDate date, @NotNull LocalTime on, LocalTime off,
            @NotBlank @Size(max = 300) String note) {
    }

    public record ModeRequest(boolean remote) {
    }

    // ---- Answers ----

    public record SessionView(long id, Instant onAt, Instant offAt, long minutes, boolean autoClosed, String imageUrl,
            String note, String offNote, String editNote) {
    }

    /**
     * One person's day.
     *
     * @param status WORKING, PRESENT, LATE, EARLY, LATE_EARLY or NOT_TURNED_OFF with a day; ABSENT, OFF, WAITING or
     *               NONE without one
     */
    public record DayView(Long id, long userId, String fullName, LocalDate date, LocalTime shiftStart, LocalTime shiftEnd,
            String status, int lateMinutes, int earlyMinutes, String lateReason, String earlyReason, boolean notTurnedOff,
            String reviewStatus, BigDecimal lateFee, String reviewedBy, String reviewNote, long workedMinutes, long extraMinutes, boolean on,
            List<SessionView> sessions) {
    }

    /** What a remote worker's own page shows. */
    public record Me(LocalDate date, boolean remote, LocalTime start, LocalTime end, boolean dayOff, boolean on, Instant onSince,
            long workedMinutes, DayView day) {
    }

    public record MonthCell(LocalDate date, String status, long workedMinutes, BigDecimal lateFee) {
    }

    /** The cards. Extra is time worked before the start or after the end of the set hours. */
    public record Summary(long workedMinutes, long extraMinutes, long daysWorked, long lateDays, long earlyDays, long notTurnedOff,
            BigDecimal lateFees) {
    }

    public record MyMonth(Summary summary, List<MonthCell> days) {
    }

    /** One card per moderator. */
    public record PersonCard(long userId, String fullName, boolean on, Instant onSince, long workedMinutes, long extraMinutes, long daysWorked,
            long lateDays, long earlyDays, long notTurnedOff, BigDecimal lateFees) {
    }

    public record Mode(boolean remote) {
    }
}
