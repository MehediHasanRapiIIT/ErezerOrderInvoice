package com.erezer.admin.expense;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.expense.dto.ExpenseDtos.ExpenseRequest;
import com.erezer.admin.file.FileService;
import com.erezer.admin.file.StoredFile;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commands that change expenses. */
@Service
public class ExpenseService {

    private final ExpenseRepository expenses;
    private final ExpenseCategoryRepository categories;
    private final FileService files;
    private final AppProperties appProperties;
    private final Clock clock;

    ExpenseService(ExpenseRepository expenses, ExpenseCategoryRepository categories, FileService files,
            AppProperties appProperties, Clock clock) {
        this.expenses = expenses;
        this.categories = categories;
        this.files = files;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    @Transactional
    public long create(ExpenseRequest request) {
        ExpenseCategory category = activeCategory(request.categoryId());
        validateDate(request.expenseDate());
        Expense expense = new Expense(request.expenseDate(), category, request.description().trim(), request.amount(),
                request.paymentMethod(), blankToNull(request.reference()), blankToNull(request.notes()),
                receipt(request.receiptFileId()));
        return expenses.save(expense).getId();
    }

    @Transactional
    public void update(long id, ExpenseRequest request) {
        Expense expense = load(id);
        if (request.version() == null || request.version() != expense.getVersion()) {
            throw ApiException.conflict("STALE_DATA", "This expense was changed by someone else. Reload and try again.");
        }
        // Keeping an expense in a since-deactivated category is fine; moving one into it is not.
        ExpenseCategory category = expense.getCategory().getId().equals(request.categoryId())
                ? expense.getCategory()
                : activeCategory(request.categoryId());
        validateDate(request.expenseDate());
        expense.update(request.expenseDate(), category, request.description().trim(), request.amount(),
                request.paymentMethod(), blankToNull(request.reference()), blankToNull(request.notes()),
                receipt(request.receiptFileId()));
    }

    /** Soft delete, so the record and its receipt stay available for audits. */
    @Transactional
    public void delete(long id) {
        load(id).markDeleted(clock.instant());
    }

    private Expense load(long id) {
        return expenses.findById(id).orElseThrow(() -> ApiException.notFound("Expense", id));
    }

    private ExpenseCategory activeCategory(long id) {
        ExpenseCategory category = categories.findById(id)
                .orElseThrow(() -> ApiException.badRequest("INVALID_CATEGORY", "The selected category does not exist"));
        if (!category.isActive()) {
            throw ApiException.badRequest("INACTIVE_CATEGORY", "Category " + category.getName() + " is no longer in use");
        }
        return category;
    }

    /** Future dates are rejected; "today" is the business day in Asia/Dhaka, not the server's UTC date. */
    private void validateDate(LocalDate date) {
        if (date.isAfter(LocalDate.now(clock.withZone(appProperties.zoneId())))) {
            throw ApiException.badRequest("FUTURE_DATE", "The expense date cannot be in the future");
        }
    }

    private StoredFile receipt(Long fileId) {
        return fileId == null ? null : files.requireReceipt(fileId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
