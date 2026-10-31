package com.erezer.admin.dailyexpense;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class DailyExpenseDtos {

    private DailyExpenseDtos() {
    }

    /**
     * @param categoryId    null means "Other", and {@code otherCategory} says what it was
     * @param receiptFileId on edit, null keeps the bill it has
     * @param version       only on edit: the version the form loaded
     */
    public record CreateRequest(
            @NotNull LocalDate date,
            Long categoryId,
            @Size(max = 60) String otherCategory,
            @NotNull @DecimalMin("0.01") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @Size(max = 500) String details,
            Long receiptFileId,
            Long version) {
    }

    public record PaidRequest(
            @NotNull @DecimalMin("0") @DecimalMax("9999999") @Digits(integer = 10, fraction = 2) BigDecimal paid) {
    }

    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record CategoryRequest(@NotBlank @Size(max = 60) String name, Boolean active) {
    }

    public record CategoryView(long id, String name, boolean active, long expenseCount) {
    }

    public record Person(long id, String name) {
    }

    public record Receipt(String url, String contentType) {
    }

    /** @param due what is still owed; empty until approved */
    public record DailyExpenseView(
            long id,
            Person requestedBy,
            LocalDate date,
            Long categoryId,
            String category,
            boolean other,
            BigDecimal amount,
            BigDecimal paid,
            BigDecimal due,
            String details,
            Receipt receipt,
            String status,
            String rejectionReason,
            String reviewedBy,
            Instant reviewedAt,
            Instant createdAt,
            long version) {
    }

    /** The cards above the list. Money is for approved expenses; the filters apply, except the status one. */
    public record Summary(long approvedCount, BigDecimal approved, BigDecimal paid, BigDecimal due, long pendingCount,
            BigDecimal pending) {
    }
}
