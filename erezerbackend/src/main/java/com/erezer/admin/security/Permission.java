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
    ORDER_DATE_EDIT("Orders", "Choose or change the order and delivered dates", false),
    ORDER_DELETE("Orders", "Delete orders", false),
    EXCHANGE_CREATE("Orders", "Create exchanges", false),
    RETURN_CREATE("Orders", "Create returns", false),

    EXPENSE_VIEW("Expenses", "View expenses", false),
    EXPENSE_CREATE("Expenses", "Add expenses", false),
    EXPENSE_EDIT("Expenses", "Edit expenses", false),
    EXPENSE_DELETE("Expenses", "Delete expenses", false),

    ASSET_VIEW("Assets", "View assets", false),
    ASSET_COSTS_VIEW("Assets", "See costs and prices", false),
    ASSET_HISTORY_VIEW("Assets", "View history", false),
    ASSET_CREATE("Assets", "Add assets", false),
    ASSET_MANAGE("Assets", "Edit asset details", false),
    ASSET_STATUS("Assets", "Change status", false),
    ASSET_REPAIRS("Assets", "Add and remove repairs", false),
    ASSET_CATEGORIES("Assets", "Manage categories", false),
    ASSET_EXPORT("Assets", "Export to Excel", false),
    ASSET_DELETE("Assets", "Delete assets", false),

    SALES_VIEW("Sold products", "View sold products", false),

    STOCK_VIEW("Stock", "View stock", false),
    STOCK_MANAGE("Stock", "Add and update stock", false),
    SIZE_MANAGE("Stock", "Manage the size list", false),

    CUSTOMER_VIEW("Customers", "View customers", false),
    CUSTOMER_EXPORT("Customers", "Export customers", false),

    COURIER_SEND("Courier", "Send orders to courier", false),
    COURIER_SETTINGS("Courier", "Manage courier settings and API keys", false),

    STAFF_MANAGE("Staff", "Manage staff and permissions (not admins)", false);

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

    /** Admin-only permissions can never be granted to a moderator (none are, at present: admins can give every one). */
    public boolean adminOnly() {
        return adminOnly;
    }

    /**
     * Permissions that come with this one: changing something in a section includes seeing it, so a staff member
     * given "Add and edit assets" also gets the Assets menu and can open what they saved.
     */
    public Set<Permission> includes() {
        return switch (this) {
            case ORDER_EDIT, ORDER_STATUS_UPDATE, ORDER_NOTE_ADD, ORDER_COURIER_COST, ORDER_DELETE, ORDER_DATE_EDIT,
                    EXCHANGE_CREATE, RETURN_CREATE, COURIER_SEND -> EnumSet.of(ORDER_VIEW);
            case ORDER_STATUS_OVERRIDE -> EnumSet.of(ORDER_VIEW, ORDER_STATUS_UPDATE);
            case EXPENSE_CREATE, EXPENSE_EDIT, EXPENSE_DELETE -> EnumSet.of(EXPENSE_VIEW);
            case ASSET_COSTS_VIEW, ASSET_HISTORY_VIEW, ASSET_CREATE, ASSET_MANAGE, ASSET_STATUS, ASSET_REPAIRS,
                    ASSET_CATEGORIES, ASSET_EXPORT, ASSET_DELETE -> EnumSet.of(ASSET_VIEW);
            case STOCK_MANAGE, SIZE_MANAGE -> EnumSet.of(STOCK_VIEW);
            case CUSTOMER_EXPORT -> EnumSet.of(CUSTOMER_VIEW);
            default -> EnumSet.noneOf(Permission.class);
        };
    }

    /** The given permissions plus everything they include. */
    public static Set<Permission> withIncluded(Set<Permission> permissions) {
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        result.addAll(permissions);
        permissions.forEach(p -> result.addAll(p.includes()));
        return result;
    }

    public static Set<Permission> grantableToModerators() {
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        Arrays.stream(values()).filter(p -> !p.adminOnly).forEach(result::add);
        return result;
    }
}
