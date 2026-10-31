package com.erezer.admin.expense;

import com.erezer.admin.expense.dto.ExpenseDtos.CategoryRequest;
import com.erezer.admin.expense.dto.ExpenseDtos.CategoryResponse;
import com.erezer.admin.notification.NotificationAction;
import com.erezer.admin.notification.Notifies;
import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/expense-categories")
class ExpenseCategoryController {

    private final ExpenseCategoryService categoryService;

    ExpenseCategoryController(ExpenseCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** All categories (active and inactive) with how many expenses use each. */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('EXPENSE_VIEW', 'EXPENSE_CREATE', 'EXPENSE_EDIT')")
    List<CategoryResponse> list() {
        return categoryService.list();
    }

    @Notifies(NotificationAction.EXPENSE_CATEGORY_CREATED)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('EXPENSE_CREATE', 'EXPENSE_EDIT')")
    CategoryResponse create(@Valid @RequestBody CategoryRequest request) {
        return categoryService.create(request);
    }

    /** Rename and/or activate/deactivate. */
    @Notifies(NotificationAction.EXPENSE_CATEGORY_EDITED)
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('EXPENSE_EDIT')")
    ResponseEntity<Void> update(@PathVariable long id, @Valid @RequestBody CategoryRequest request) {
        categoryService.update(id, request);
        return ResponseEntity.noContent().build();
    }

    @Notifies(NotificationAction.EXPENSE_CATEGORY_DELETED)
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('EXPENSE_DELETE')")
    ResponseEntity<Void> delete(@PathVariable long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
