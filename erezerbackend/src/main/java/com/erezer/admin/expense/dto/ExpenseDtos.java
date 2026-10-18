package com.erezer.admin.expense.dto;

import com.erezer.admin.order.PaymentMethod;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ExpenseDtos {

    private ExpenseDtos() {
    }

    // ---- Requests -------------------------------------------------------------

    /** @param version required on update for optimistic locking; ignored on create */
    public record ExpenseRequest(
            @NotNull LocalDate expenseDate,
            @NotNull Long categoryId,
            @NotBlank @Size(max = 200) String description,
            @NotNull @DecimalMin("0.01") @DecimalMax("99999999") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotNull PaymentMethod paymentMethod,
            @Size(max = 80) String reference,
            @Size(max = 1000) String notes,
            Long receiptFileId,
            Long version) {
    }

    public record CategoryRequest(@NotBlank @Size(max = 60) String name, Boolean active) {
    }

    // ---- Responses ------------------------------------------------------------

    public record ExpenseResponse(
            long id,
            LocalDate expenseDate,
            CategoryRef category,
            String description,
            BigDecimal amount,
            PaymentMethod paymentMethod,
            String reference,
            String notes,
            Receipt receipt,
            UserRef createdBy,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    public record CategoryRef(long id, String name) {
    }

    public record Receipt(long fileId, String url, String contentType, String fileName) {
    }

    public record UserRef(long id, String name) {
    }

    /** A page of expenses plus aggregates over every expense matching the filters (not just this page). */
    public record ExpensePage(
            List<ExpenseResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            BigDecimal totalAmount,
            List<CategoryTotal> byCategory) {
    }

    public record CategoryTotal(long categoryId, String name, BigDecimal total, long count) {
    }

    public record CategoryResponse(long id, String name, boolean active, long expenseCount) {
    }
}
