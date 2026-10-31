package com.erezer.admin.dailyexpense;

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

/** What an employee or moderator spent, waiting for an admin or already decided. */
@Entity
@Table(name = "daily_expenses")
public class DailyExpense extends AuditableEntity {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    /** Null means "Other". */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private DailyExpenseCategory category;

    @Column(name = "other_category", length = 60)
    private String otherCategory;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "details", length = 500)
    private String details;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id")
    private StoredFile receipt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(name = "paid", nullable = false, precision = 12, scale = 2)
    private BigDecimal paid = BigDecimal.ZERO;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected DailyExpense() {
        // for JPA
    }

    DailyExpense(long userId, LocalDate expenseDate, DailyExpenseCategory category, String otherCategory, BigDecimal amount,
            String details, StoredFile receipt) {
        this.userId = userId;
        update(expenseDate, category, otherCategory, amount, details, receipt);
    }

    final void update(LocalDate expenseDate, DailyExpenseCategory category, String otherCategory, BigDecimal amount, String details,
            StoredFile receipt) {
        this.expenseDate = expenseDate;
        this.category = category;
        this.otherCategory = otherCategory;
        this.amount = amount.setScale(2, RoundingMode.HALF_UP);
        this.details = details;
        this.receipt = receipt;
    }

    void approve(BigDecimal paid, long by, Instant at) {
        this.status = Status.APPROVED;
        setPaid(paid);
        this.reviewedBy = by;
        this.reviewedAt = at;
    }

    void reject(String reason, long by, Instant at) {
        this.status = Status.REJECTED;
        this.rejectionReason = reason;
        this.reviewedBy = by;
        this.reviewedAt = at;
    }

    void setPaid(BigDecimal paid) {
        this.paid = paid.setScale(2, RoundingMode.HALF_UP);
    }

    /** What is still owed; null until approved. */
    public BigDecimal getDue() {
        return status == Status.APPROVED ? amount.subtract(paid) : null;
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDate getExpenseDate() {
        return expenseDate;
    }

    public DailyExpenseCategory getCategory() {
        return category;
    }

    public String getOtherCategory() {
        return otherCategory;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getDetails() {
        return details;
    }

    public StoredFile getReceipt() {
        return receipt;
    }

    public Status getStatus() {
        return status;
    }

    public BigDecimal getPaid() {
        return paid;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
