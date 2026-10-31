package com.erezer.admin.attendance;

import com.erezer.admin.common.persistence.AuditableEntity;
import com.erezer.admin.file.StoredFile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** One employee's day: check-in, check-out, and the admin's review when they were late, early or away. */
@Entity
@Table(name = "attendance_records")
public class AttendanceRecord extends AuditableEntity {

    public enum Review {
        NONE,
        PENDING,
        APPROVED
    }

    /** Where someone was when they checked in or out. */
    record Place(Double latitude, Double longitude, Long locationId, Integer distanceM, boolean inRange) {
    }

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "work_date", nullable = false, updatable = false)
    private LocalDate workDate;

    @Column(name = "shift_start", nullable = false, updatable = false)
    private LocalTime shiftStart;

    @Column(name = "shift_end", nullable = false, updatable = false)
    private LocalTime shiftEnd;

    @Column(name = "check_in_at")
    private Instant checkInAt;

    @Column(name = "check_in_latitude")
    private Double checkInLatitude;

    @Column(name = "check_in_longitude")
    private Double checkInLongitude;

    @Column(name = "check_in_location_id")
    private Long checkInLocationId;

    @Column(name = "check_in_distance_m")
    private Integer checkInDistanceM;

    @Column(name = "check_in_in_range", nullable = false)
    private boolean checkInInRange;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "check_in_photo_id")
    private StoredFile checkInPhoto;

    @Column(name = "check_in_reason", length = 500)
    private String checkInReason;

    @Column(name = "late_minutes", nullable = false)
    private int lateMinutes;

    @Column(name = "check_out_at")
    private Instant checkOutAt;

    @Column(name = "check_out_latitude")
    private Double checkOutLatitude;

    @Column(name = "check_out_longitude")
    private Double checkOutLongitude;

    @Column(name = "check_out_location_id")
    private Long checkOutLocationId;

    @Column(name = "check_out_distance_m")
    private Integer checkOutDistanceM;

    @Column(name = "check_out_in_range", nullable = false)
    private boolean checkOutInRange;

    @Column(name = "check_out_reason", length = 500)
    private String checkOutReason;

    @Column(name = "early_minutes", nullable = false)
    private int earlyMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 10)
    private Review reviewStatus = Review.NONE;

    @Column(name = "late_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal lateFee = BigDecimal.ZERO;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "edited_by")
    private Long editedBy;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "edit_note", length = 500)
    private String editNote;

    protected AttendanceRecord() {
        // for JPA
    }

    AttendanceRecord(long userId, LocalDate workDate, LocalTime shiftStart, LocalTime shiftEnd) {
        this.userId = userId;
        this.workDate = workDate;
        this.shiftStart = shiftStart;
        this.shiftEnd = shiftEnd;
    }

    void checkIn(Instant at, Place place, StoredFile photo, String reason, int lateMinutes, boolean needsReview) {
        this.checkInAt = at;
        this.checkInLatitude = place.latitude();
        this.checkInLongitude = place.longitude();
        this.checkInLocationId = place.locationId();
        this.checkInDistanceM = place.distanceM();
        this.checkInInRange = place.inRange();
        this.checkInPhoto = photo;
        this.checkInReason = reason;
        this.lateMinutes = lateMinutes;
        if (needsReview) {
            this.reviewStatus = Review.PENDING;
        }
    }

    /** A late check-in may already be approved; leaving early then sends the day back for review. */
    void checkOut(Instant at, Place place, String reason, int earlyMinutes, boolean needsReview) {
        this.checkOutAt = at;
        this.checkOutLatitude = place.latitude();
        this.checkOutLongitude = place.longitude();
        this.checkOutLocationId = place.locationId();
        this.checkOutDistanceM = place.distanceM();
        this.checkOutInRange = place.inRange();
        this.checkOutReason = reason;
        this.earlyMinutes = earlyMinutes;
        if (needsReview) {
            this.reviewStatus = Review.PENDING;
        }
    }

    void review(BigDecimal fee, String note, long by, Instant at) {
        this.lateFee = fee.setScale(2, RoundingMode.HALF_UP);
        this.reviewNote = note;
        this.reviewedBy = by;
        this.reviewedAt = at;
        this.reviewStatus = Review.APPROVED;
    }

    /** A correction by hand: the times are the admin's, so nothing is left waiting for review. */
    void correct(Instant checkIn, Instant checkOut, int lateMinutes, int earlyMinutes, String note, long by, Instant at) {
        this.checkInAt = checkIn;
        this.checkOutAt = checkOut;
        this.lateMinutes = lateMinutes;
        this.earlyMinutes = earlyMinutes;
        this.editNote = note;
        this.editedBy = by;
        this.editedAt = at;
        this.reviewStatus = lateMinutes > 0 || earlyMinutes > 0 ? Review.APPROVED : Review.NONE;
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public LocalTime getShiftStart() {
        return shiftStart;
    }

    public LocalTime getShiftEnd() {
        return shiftEnd;
    }

    public Instant getCheckInAt() {
        return checkInAt;
    }

    public Double getCheckInLatitude() {
        return checkInLatitude;
    }

    public Double getCheckInLongitude() {
        return checkInLongitude;
    }

    public Long getCheckInLocationId() {
        return checkInLocationId;
    }

    public Integer getCheckInDistanceM() {
        return checkInDistanceM;
    }

    public boolean isCheckInInRange() {
        return checkInInRange;
    }

    public StoredFile getCheckInPhoto() {
        return checkInPhoto;
    }

    public String getCheckInReason() {
        return checkInReason;
    }

    public int getLateMinutes() {
        return lateMinutes;
    }

    public Instant getCheckOutAt() {
        return checkOutAt;
    }

    public Double getCheckOutLatitude() {
        return checkOutLatitude;
    }

    public Double getCheckOutLongitude() {
        return checkOutLongitude;
    }

    public Long getCheckOutLocationId() {
        return checkOutLocationId;
    }

    public Integer getCheckOutDistanceM() {
        return checkOutDistanceM;
    }

    public boolean isCheckOutInRange() {
        return checkOutInRange;
    }

    public String getCheckOutReason() {
        return checkOutReason;
    }

    public int getEarlyMinutes() {
        return earlyMinutes;
    }

    public Review getReviewStatus() {
        return reviewStatus;
    }

    public BigDecimal getLateFee() {
        return lateFee;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public String getEditNote() {
        return editNote;
    }
}
