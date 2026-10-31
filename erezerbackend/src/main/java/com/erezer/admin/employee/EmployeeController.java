package com.erezer.admin.employee;

import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.employee.EmployeeDtos.EmployeeRequest;
import com.erezer.admin.employee.EmployeeDtos.EmployeeSummary;
import com.erezer.admin.employee.EmployeeDtos.EmployeeView;
import com.erezer.admin.notification.NotificationAction;
import com.erezer.admin.notification.Notifies;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
@RequestMapping("/api/employees")
class EmployeeController {

    private static final int MAX_PAGE_SIZE = 100;

    private final EmployeeService employees;

    EmployeeController(EmployeeService employees) {
        this.employees = employees;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('EMPLOYEE_VIEW')")
    PageResponse<EmployeeSummary> list(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return employees.list(q, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.asc("fullName").ignoreCase(), Sort.Order.asc("id"))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('EMPLOYEE_VIEW')")
    EmployeeView get(@PathVariable long id) {
        return employees.get(id);
    }

    @Notifies(NotificationAction.EMPLOYEE_REGISTERED)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('EMPLOYEE_CREATE')")
    EmployeeView create(@Valid @RequestBody EmployeeRequest request) {
        return employees.create(request);
    }

    @Notifies(NotificationAction.EMPLOYEE_EDITED)
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('EMPLOYEE_EDIT')")
    EmployeeView update(@PathVariable long id, @Valid @RequestBody EmployeeRequest request) {
        return employees.update(id, request);
    }

    @Notifies(NotificationAction.EMPLOYEE_DELETED)
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('EMPLOYEE_DELETE')")
    ResponseEntity<Void> delete(@PathVariable long id) {
        employees.delete(id);
        return ResponseEntity.noContent().build();
    }
}
