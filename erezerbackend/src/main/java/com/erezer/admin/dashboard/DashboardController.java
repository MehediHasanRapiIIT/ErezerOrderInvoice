package com.erezer.admin.dashboard;

import com.erezer.admin.config.AppProperties;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
class DashboardController {

    private final DashboardService dashboardService;
    private final AppProperties appProperties;

    DashboardController(DashboardService dashboardService, AppProperties appProperties) {
        this.dashboardService = dashboardService;
        this.appProperties = appProperties;
    }

    /** Defaults to the current month so far (Asia/Dhaka). Both dates are inclusive. */
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    DashboardSummary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now(appProperties.zoneId());
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        return dashboardService.summary(start, end);
    }
}
