package com.erezer.admin.security;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Granular capabilities checked with {@code @PreAuthorize("hasAuthority('...')")}.
 * The enum name is the authority string, so renaming a constant is a breaking change
 * (it is stored in {@code user_permissions} and embedded in issued tokens).
 */
public enum Permission {

    DASHBOARD_VIEW("Dashboard", "View dashboard", false),

    ORDER_VIEW("Orders", "View orders", false),
    ORDER_CREATE("Orders", "Create orders", false),
    ORDER_EDIT("Orders", "Edit orders", false),
    ORDER_STATUS_UPDATE("Orders", "Update order status", false),
    ORDER_STATUS_OVERRIDE("Orders", "Change status freely (any status, any time)", false),
    ORDER_NOTE_ADD("Orders", "Add internal notes", false),
    ORDER_COURIER_COST("Orders", "Record the courier cost", false),
    ORDER_DELETE("Orders", "Delete orders", false),
    EXCHANGE_CREATE("Orders", "Create exchanges", false),
    RETURN_CREATE("Orders", "Create returns", false),

    EXPENSE_VIEW("Expenses", "View expenses", false),
    EXPENSE_CREATE("Expenses", "Add expenses", false),
    EXPENSE_EDIT("Expenses", "Edit expenses", false),
    EXPENSE_DELETE("Expenses", "Delete expenses", false),

    ASSET_VIEW("Assets", "View assets", false),
    ASSET_MANAGE("Assets", "Add and edit assets", false),
    ASSET_DELETE("Assets", "Delete assets", false),

    SALES_VIEW("Sold products", "View sold products", false),

    STOCK_VIEW("Stock", "View stock", false),
    STOCK_MANAGE("Stock", "Add and update stock", false),
    SIZE_MANAGE("Stock", "Manage the size list", true),

    CUSTOMER_VIEW("Customers", "View customers", false),
    CUSTOMER_EXPORT("Customers", "Export customers", false),

    COURIER_SEND("Courier", "Send orders to courier", false),
    COURIER_SETTINGS("Courier", "Manage courier settings and API keys", true),

    STAFF_MANAGE("Staff", "Manage staff and permissions", true);

    private final String group;
    private final String label;
    private final boolean adminOnly;

    Permission(String group, String label, boolean adminOnly) {
        this.group = group;
        this.label = label;
        this.adminOnly = adminOnly;
    }

    public String group() {
        return group;
    }

    public String label() {
        return label;
    }

    /** Admin-only permissions can never be granted to a moderator. */
    public boolean adminOnly() {
        return adminOnly;
    }

    public static Set<Permission> grantableToModerators() {
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        Arrays.stream(values()).filter(p -> !p.adminOnly).forEach(result::add);
        return result;
    }
}
