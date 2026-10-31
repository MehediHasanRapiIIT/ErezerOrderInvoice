package com.erezer.admin.expense;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.expense.dto.ExpenseDtos.ExpensePage;
import com.erezer.admin.expense.dto.ExpenseDtos.ExpenseRequest;
import com.erezer.admin.expense.dto.ExpenseDtos.ExpenseResponse;
import com.erezer.admin.notification.NotificationAction;
import com.erezer.admin.notification.Notifies;
import com.erezer.admin.order.PaymentMethod;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
@RequestMapping("/api/expenses")
class ExpenseController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Map<String, String> SORTABLE = Map.of(
            "expenseDate", "expenseDate",
            "amount", "amount",
            "createdAt", "createdAt");

    private final ExpenseService expenseService;
    private final ExpenseQueryService queryService;
    private final ExpenseExportService exportService;
    private final AppProperties appProperties;

    ExpenseController(ExpenseService expenseService, ExpenseQueryService queryService,
            ExpenseExportService exportService, AppProperties appProperties) {
        this.expenseService = expenseService;
        this.queryService = queryService;
        this.exportService = exportService;
        this.appProperties = appProperties;
    }

    /** Page of expenses plus the total amount and per-category totals of everything matching the filters. */
    @GetMapping
    @PreAuthorize("hasAuthority('EXPENSE_VIEW')")
    ExpensePage list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "expenseDate,desc") String sort) {
        ExpenseFilter filter = filter(q, categoryId, paymentMethod, from, to, minAmount, maxAmount);
        return queryService.search(filter,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), parseSort(sort)));
    }

    @GetMapping("/export.xlsx")
    @PreAuthorize("hasAuthority('EXPENSE_VIEW')")
    ResponseEntity<byte[]> export(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(defaultValue = "expenseDate,desc") String sort) {
        byte[] workbook = exportService.export(filter(q, categoryId, paymentMethod, from, to, minAmount, maxAmount),
                parseSort(sort));
        String fileName = "expenses-" + LocalDate.now(appProperties.zoneId()) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .body(workbook);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('EXPENSE_VIEW')")
    ExpenseResponse get(@PathVariable long id) {
        return queryService.get(id);
    }

    @Notifies(NotificationAction.EXPENSE_CREATED)
    @PostMapping
    @PreAuthorize("hasAuthority('EXPENSE_CREATE')")
    ResponseEntity<ExpenseResponse> create(@Valid @RequestBody ExpenseRequest request) {
        long id = expenseService.create(request);
        return ResponseEntity.created(URI.create("/api/expenses/" + id)).body(queryService.get(id));
    }

    @Notifies(NotificationAction.EXPENSE_EDITED)
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('EXPENSE_EDIT')")
    ExpenseResponse update(@PathVariable long id, @Valid @RequestBody ExpenseRequest request) {
        expenseService.update(id, request);
        return queryService.get(id);
    }

    @Notifies(NotificationAction.EXPENSE_DELETED)
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('EXPENSE_DELETE')")
    ResponseEntity<Void> delete(@PathVariable long id) {
        expenseService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static ExpenseFilter filter(String q, Long categoryId, PaymentMethod paymentMethod, LocalDate from,
            LocalDate to, BigDecimal minAmount, BigDecimal maxAmount) {
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "The start date must be on or before the end date");
        }
        if (minAmount != null && maxAmount != null && minAmount.compareTo(maxAmount) > 0) {
            throw ApiException.badRequest("INVALID_AMOUNT_RANGE", "The minimum amount must not exceed the maximum");
        }
        return new ExpenseFilter(q, categoryId, paymentMethod, from, to, minAmount, maxAmount);
    }

    private static Sort parseSort(String sort) {
        String[] parts = sort.split(",");
        String property = SORTABLE.get(parts[0].trim());
        if (property == null) {
            throw ApiException.badRequest("INVALID_SORT", "Cannot sort by " + parts[0]);
        }
        boolean ascending = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc");
        Sort primary = ascending ? Sort.by(property).ascending() : Sort.by(property).descending();
        return primary.and(Sort.by("id").descending());
    }
}
