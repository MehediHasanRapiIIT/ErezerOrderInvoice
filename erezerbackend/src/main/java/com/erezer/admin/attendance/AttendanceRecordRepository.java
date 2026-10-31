package com.erezer.admin.attendance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {

    Optional<AttendanceRecord> findByUserIdAndWorkDate(long userId, LocalDate workDate);

    List<AttendanceRecord> findByWorkDate(LocalDate workDate);

    List<AttendanceRecord> findByWorkDateBetween(LocalDate from, LocalDate to);

    List<AttendanceRecord> findByUserIdAndWorkDateBetween(long userId, LocalDate from, LocalDate to);

    Page<AttendanceRecord> findByReviewStatus(AttendanceRecord.Review status, Pageable pageable);

    Page<AttendanceRecord> findByReviewStatusAndUserId(AttendanceRecord.Review status, long userId, Pageable pageable);
}
