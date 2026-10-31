package com.erezer.admin.reject;

import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.notification.NotificationAction;
import com.erezer.admin.notification.Notifies;
import com.erezer.admin.reject.Reject.Kind;
import com.erezer.admin.reject.RejectDtos.RejectRequest;
import com.erezer.admin.reject.RejectDtos.RejectView;
import com.erezer.admin.reject.RejectDtos.Summary;
import com.erezer.admin.security.CurrentUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
@RequestMapping("/api/rejects")
class RejectController {

    private static final int MAX_PAGE_SIZE = 100;

    private final RejectService rejects;

    RejectController(RejectService rejects) {
        this.rejects = rejects;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('REJECT_VIEW')")
    PageResponse<RejectView> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Kind kind,
            @RequestParam(required = false) Boolean fromStock,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return rejects.list(from, to, kind, fromStock, q, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("rejectDate"), Sort.Order.desc("id"))));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('REJECT_VIEW')")
    Summary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q) {
        return rejects.summary(from, to, q);
    }

    @Notifies(NotificationAction.REJECT_ADDED)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('REJECT_CREATE')")
    RejectView create(@Valid @RequestBody RejectRequest request) {
        return rejects.create(request, CurrentUser.id());
    }

    @Notifies(NotificationAction.REJECT_EDITED)
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('REJECT_EDIT')")
    RejectView update(@PathVariable long id, @Valid @RequestBody RejectRequest request) {
        return rejects.update(id, request, CurrentUser.id());
    }

    @Notifies(NotificationAction.REJECT_DELETED)
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('REJECT_DELETE')")
    ResponseEntity<Void> delete(@PathVariable long id) {
        rejects.delete(id, CurrentUser.id());
        return ResponseEntity.noContent().build();
    }
}
