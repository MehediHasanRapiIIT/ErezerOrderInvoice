package com.erezer.admin.security;

import java.util.Arrays;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalog of permissions for the staff-management screen, so the UI never hard-codes the list.
 */
@RestController
@RequestMapping("/api/permissions")
class PermissionController {

    @GetMapping
    @PreAuthorize("hasAuthority('STAFF_MANAGE')")
    List<PermissionResponse> list() {
        return Arrays.stream(Permission.values())
                .map(p -> new PermissionResponse(p.name(), p.group(), p.label(), p.adminOnly()))
                .toList();
    }

    record PermissionResponse(String name, String group, String label, boolean adminOnly) {
    }
}
