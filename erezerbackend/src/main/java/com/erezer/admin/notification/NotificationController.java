package com.erezer.admin.notification;

import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.notification.NotificationDtos.ActionView;
import com.erezer.admin.notification.NotificationDtos.DeviceRequest;
import com.erezer.admin.notification.NotificationDtos.NotificationView;
import com.erezer.admin.notification.NotificationDtos.PersonView;
import com.erezer.admin.notification.NotificationDtos.PushConfig;
import com.erezer.admin.notification.NotificationDtos.Rule;
import com.erezer.admin.notification.NotificationDtos.RuleView;
import com.erezer.admin.notification.NotificationDtos.SettingsRequest;
import com.erezer.admin.notification.NotificationDtos.SettingsView;
import com.erezer.admin.notification.NotificationDtos.Subscription;
import com.erezer.admin.notification.NotificationDtos.TestResult;
import com.erezer.admin.notification.NotificationDtos.Unread;
import com.erezer.admin.security.CurrentUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
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

@RestController
@RequestMapping("/api/notifications")
class NotificationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationService notifications;
    private final NotificationSetupService setup;

    NotificationController(NotificationService notifications, NotificationSetupService setup) {
        this.notifications = notifications;
        this.setup = setup;
    }

    // ---- The person's own ----

    @GetMapping
    @PreAuthorize("hasAuthority('NOTIFICATION_VIEW')")
    PageResponse<NotificationView> list(
            @RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(required = false) String section,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return notifications.list(CurrentUser.id(), new NotificationService.Filter(unread, section, actorId, from, to),
                Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/unread-count")
    @PreAuthorize("hasAuthority('NOTIFICATION_VIEW')")
    Unread unread() {
        return new Unread(notifications.unread(CurrentUser.id()));
    }

    @GetMapping("/actions")
    @PreAuthorize("hasAuthority('NOTIFICATION_VIEW')")
    List<ActionView> actions() {
        return setup.catalog();
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("hasAuthority('NOTIFICATION_VIEW')")
    ResponseEntity<Void> read(@PathVariable long id) {
        notifications.markRead(CurrentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @PreAuthorize("hasAuthority('NOTIFICATION_VIEW')")
    ResponseEntity<Void> readAll() {
        notifications.markAllRead(CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    // ---- This phone or browser ----

    @GetMapping("/push-config")
    @PreAuthorize("hasAuthority('NOTIFICATION_VIEW')")
    PushConfig pushConfig() {
        return setup.pushConfig();
    }

    @PutMapping("/devices")
    @PreAuthorize("hasAuthority('NOTIFICATION_VIEW')")
    ResponseEntity<Void> addDevice(@Valid @RequestBody DeviceRequest request) {
        setup.addDevice(CurrentUser.id(), request.token());
        return ResponseEntity.noContent().build();
    }

    /** Signing out stops pushes here, even for someone who has since lost the permission. */
    @PostMapping("/devices/remove")
    ResponseEntity<Void> removeDevice(@Valid @RequestBody DeviceRequest request) {
        setup.removeDevice(CurrentUser.id(), request.token());
        return ResponseEntity.noContent().build();
    }

    // ---- Setup ----

    @GetMapping("/rules")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    List<RuleView> rules() {
        return setup.ruleViews();
    }

    @PutMapping("/rules")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    @Notifies(NotificationAction.NOTIFICATION_SETUP_CHANGED)
    ResponseEntity<Void> saveRules(@RequestBody List<@Valid Rule> rules) {
        setup.saveRules(rules);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/people")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    List<PersonView> people() {
        return setup.people();
    }

    @GetMapping("/people/{userId}")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    List<Subscription> subscriptions(@PathVariable long userId) {
        return setup.subscriptionsOf(userId);
    }

    @PutMapping("/people/{userId}")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    @Notifies(NotificationAction.NOTIFICATION_SETUP_CHANGED)
    ResponseEntity<Void> saveSubscriptions(@PathVariable long userId, @RequestBody List<@Valid Subscription> subscriptions) {
        setup.saveSubscriptions(userId, subscriptions);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    SettingsView settings() {
        return setup.settingsView();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    @Notifies(NotificationAction.NOTIFICATION_SETUP_CHANGED)
    SettingsView saveSettings(@Valid @RequestBody SettingsRequest request) {
        setup.saveSettings(request);
        return setup.settingsView();
    }

    @PostMapping("/settings/test")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    TestResult test() {
        int[] result = notifications.test(CurrentUser.id());
        return new TestResult(result[0], result[1]);
    }
}
