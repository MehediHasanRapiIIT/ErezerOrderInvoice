package com.erezer.admin.dailyexpense;

import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.dailyexpense.DailyExpense.Status;
import com.erezer.admin.dailyexpense.DailyExpenseDtos.CategoryRequest;
import com.erezer.admin.dailyexpense.DailyExpenseDtos.CategoryView;
import com.erezer.admin.dailyexpense.DailyExpenseDtos.CreateRequest;
import com.erezer.admin.dailyexpense.DailyExpenseDtos.DailyExpenseView;
import com.erezer.admin.dailyexpense.DailyExpenseDtos.PaidRequest;
import com.erezer.admin.dailyexpense.DailyExpenseDtos.Person;
import com.erezer.admin.dailyexpense.DailyExpenseDtos.RejectRequest;
import com.erezer.admin.dailyexpense.DailyExpenseDtos.Summary;
import com.erezer.admin.notification.NotificationAction;
import com.erezer.admin.notification.Notifies;
import com.erezer.admin.security.CurrentUser;
import com.erezer.admin.security.Permission;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DailyExpenseController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DailyExpenseService service;

    DailyExpenseController(DailyExpenseService service) {
        this.service = service;
    }

    // ---- Expenses ----

    /** Everyone's for those who may see them, otherwise only the person's own. */
    @GetMapping("/api/daily-expenses")
    @PreAuthorize("hasAnyAuthority('DAILY_EXPENSE_CREATE', 'DAILY_EXPENSE_VIEW')")
    PageResponse<DailyExpenseView> list(
            @RequestParam(required = false) Status status,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(filter(status, categoryId, userId, from, to, q), Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/api/daily-expenses/summary")
    @PreAuthorize("hasAnyAuthority('DAILY_EXPENSE_CREATE', 'DAILY_EXPENSE_VIEW')")
    Summary summary(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q) {
        return service.summary(filter(null, categoryId, userId, from, to, q));
    }

    @GetMapping("/api/daily-expenses/people")
    @PreAuthorize("hasAuthority('DAILY_EXPENSE_VIEW')")
    List<Person> people() {
        return service.people();
    }

    private static DailyExpenseService.Filter filter(Status status, Long categoryId, Long userId, LocalDate from, LocalDate to, String q) {
        Long person = CurrentUser.has(Permission.DAILY_EXPENSE_VIEW) ? userId : Long.valueOf(CurrentUser.id());
        return new DailyExpenseService.Filter(status, categoryId, person, from, to, q);
    }

    @Notifies(NotificationAction.DAILY_EXPENSE_CREATED)
    @PostMapping("/api/daily-expenses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DAILY_EXPENSE_CREATE')")
    DailyExpenseView create(@Valid @RequestBody CreateRequest request) {
        return service.create(request, CurrentUser.id());
    }

    @Notifies(NotificationAction.DAILY_EXPENSE_EDITED)
    @PutMapping("/api/daily-expenses/{id}")
    @PreAuthorize("hasAuthority('DAILY_EXPENSE_EDIT')")
    DailyExpenseView update(@PathVariable long id, @Valid @RequestBody CreateRequest request) {
        return service.update(id, request, CurrentUser.id());
    }

    /** Takes back one's own, or with the delete permission removes anyone's. */
    @Notifies(NotificationAction.DAILY_EXPENSE_CANCELLED)
    @DeleteMapping("/api/daily-expenses/{id}")
    @PreAuthorize("hasAnyAuthority('DAILY_EXPENSE_CREATE', 'DAILY_EXPENSE_DELETE')")
    ResponseEntity<Void> delete(@PathVariable long id) {
        boolean mayDeleteAny = CurrentUser.has(Permission.DAILY_EXPENSE_DELETE);
        service.delete(id, CurrentUser.id(), mayDeleteAny);
        if (mayDeleteAny) {
            Notifies.Context.as(NotificationAction.DAILY_EXPENSE_DELETED);
        }
        return ResponseEntity.noContent().build();
    }

    @Notifies(NotificationAction.DAILY_EXPENSE_APPROVED)
    @PostMapping("/api/daily-expenses/{id}/approve")
    @PreAuthorize("hasAuthority('DAILY_EXPENSE_APPROVE')")
    DailyExpenseView approve(@PathVariable long id, @Valid @RequestBody PaidRequest request) {
        return service.approve(id, request.paid(), CurrentUser.id());
    }

    @Notifies(NotificationAction.DAILY_EXPENSE_REJECTED)
    @PostMapping("/api/daily-expenses/{id}/reject")
    @PreAuthorize("hasAuthority('DAILY_EXPENSE_APPROVE')")
    DailyExpenseView reject(@PathVariable long id, @Valid @RequestBody RejectRequest request) {
        return service.reject(id, request.reason(), CurrentUser.id());
    }

    @Notifies(NotificationAction.DAILY_EXPENSE_PAID)
    @PostMapping("/api/daily-expenses/{id}/paid")
    @PreAuthorize("hasAuthority('DAILY_EXPENSE_APPROVE')")
    DailyExpenseView updatePaid(@PathVariable long id, @Valid @RequestBody PaidRequest request) {
        return service.updatePaid(id, request.paid(), CurrentUser.id());
    }

    // ---- Categories ----

    @GetMapping("/api/daily-expense-categories")
    @PreAuthorize("hasAnyAuthority('DAILY_EXPENSE_CREATE', 'DAILY_EXPENSE_VIEW')")
    List<CategoryView> categories() {
        return service.categories();
    }

    @Notifies(NotificationAction.DAILY_CATEGORY_CREATED)
    @PostMapping("/api/daily-expense-categories")
    @PreAuthorize("hasAuthority('DAILY_EXPENSE_CATEGORIES')")
    ResponseEntity<Void> createCategory(@Valid @RequestBody CategoryRequest request) {
        service.createCategory(request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Notifies(NotificationAction.DAILY_CATEGORY_EDITED)
    @PutMapping("/api/daily-expense-categories/{id}")
    @PreAuthorize("hasAuthority('DAILY_EXPENSE_CATEGORIES')")
    ResponseEntity<Void> updateCategory(@PathVariable long id, @Valid @RequestBody CategoryRequest request) {
        service.updateCategory(id, request);
        return ResponseEntity.noContent().build();
    }

    @Notifies(NotificationAction.DAILY_CATEGORY_DELETED)
    @DeleteMapping("/api/daily-expense-categories/{id}")
    @PreAuthorize("hasAuthority('DAILY_EXPENSE_CATEGORIES')")
    ResponseEntity<Void> deleteCategory(@PathVariable long id) {
        service.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }
}
