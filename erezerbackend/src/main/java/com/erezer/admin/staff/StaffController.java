package com.erezer.admin.staff;

import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.notification.NotificationAction;
import com.erezer.admin.notification.Notifies;
import com.erezer.admin.security.CurrentUser;
import com.erezer.admin.security.Role;
import com.erezer.admin.staff.StaffDtos.CreateStaffRequest;
import com.erezer.admin.staff.StaffDtos.PasswordRequestView;
import com.erezer.admin.staff.StaffDtos.ResetPasswordRequest;
import com.erezer.admin.staff.StaffDtos.StaffResponse;
import com.erezer.admin.staff.StaffDtos.UpdateStaffRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff management; admin-only through the STAFF_MANAGE permission. */
@RestController
@RequestMapping("/api/staff")
@PreAuthorize("hasAuthority('STAFF_MANAGE')")
class StaffController {

    private final StaffService staffService;
    private final PasswordResetRequests passwordRequests;

    StaffController(StaffService staffService, PasswordResetRequests passwordRequests) {
        this.staffService = staffService;
        this.passwordRequests = passwordRequests;
    }

    @GetMapping
    PageResponse<StaffResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Sort sort = Sort.by(Sort.Order.desc("active"), Sort.Order.asc("role"), Sort.Order.asc("fullName"));
        return staffService.search(q, role, active, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), sort));
    }

    @GetMapping("/{id}")
    StaffResponse get(@PathVariable long id) {
        return staffService.get(id);
    }

    @Notifies(NotificationAction.STAFF_CREATED)
    @PostMapping
    ResponseEntity<StaffResponse> create(@Valid @RequestBody CreateStaffRequest request) {
        StaffResponse created = staffService.create(request, CurrentUser.id());
        return ResponseEntity.created(URI.create("/api/staff/" + created.id())).body(created);
    }

    @Notifies(NotificationAction.STAFF_EDITED)
    @PutMapping("/{id}")
    StaffResponse update(@PathVariable long id, @Valid @RequestBody UpdateStaffRequest request) {
        return staffService.update(id, request, CurrentUser.id());
    }

    @Notifies(NotificationAction.STAFF_ACTIVATED)
    @PostMapping("/{id}/activate")
    StaffResponse activate(@PathVariable long id) {
        return staffService.setActive(id, true, CurrentUser.id());
    }

    @Notifies(NotificationAction.STAFF_DEACTIVATED)
    @PostMapping("/{id}/deactivate")
    StaffResponse deactivate(@PathVariable long id) {
        return staffService.setActive(id, false, CurrentUser.id());
    }

    @Notifies(NotificationAction.STAFF_PASSWORD_RESET)
    @PostMapping("/{id}/reset-password")
    StaffResponse resetPassword(@PathVariable long id, @Valid @RequestBody ResetPasswordRequest request) {
        return staffService.resetPassword(id, request.newPassword(), CurrentUser.id());
    }

    /** "Forgot password" requests from the sign-in page, waiting for an admin to set a new password. */
    @GetMapping("/password-requests")
    List<PasswordRequestView> passwordRequests() {
        return passwordRequests.open();
    }

    @Notifies(NotificationAction.STAFF_PASSWORD_REQUEST_DISMISSED)
    @PostMapping("/password-requests/{requestId}/dismiss")
    ResponseEntity<Void> dismissPasswordRequest(@PathVariable long requestId) {
        passwordRequests.dismiss(requestId, CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    @Notifies(NotificationAction.STAFF_UNLOCKED)
    @PostMapping("/{id}/unlock")
    StaffResponse unlock(@PathVariable long id) {
        return staffService.unlock(id, CurrentUser.id());
    }
}
