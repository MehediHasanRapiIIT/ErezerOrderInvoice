package com.erezer.admin.notification;

import static com.erezer.admin.notification.NotificationTarget.*;

/**
 * Every action that can send a notification. A new action only needs a line here and {@link Notifies} on its endpoint;
 * the Notifications menu then offers it for setup. A test fails if an endpoint that changes data has neither.
 */
public enum NotificationAction {

    ORDER_CREATED("Orders", "Order created", ORDER),
    ORDER_EDITED("Orders", "Order edited", ORDER),
    ORDER_STATUS_CHANGED("Orders", "Order status changed", ORDER),
    ORDER_DELIVERED_DATE_CHANGED("Orders", "Delivered date changed", ORDER),
    ORDER_DATES_CHANGED("Orders", "Order dates changed in bulk", ORDERS),
    ORDER_COURIER_COST_CHANGED("Orders", "Courier cost recorded", ORDER),
    ORDER_NOTE_ADDED("Orders", "Internal note added", ORDER),
    ORDER_DELETED("Orders", "Order deleted", ORDER),
    EXCHANGE_CREATED("Orders", "Exchange created", ORDER),
    RETURN_CREATED("Orders", "Return created", ORDER),
    RETURN_CORRECTED("Orders", "Return corrected", RETURN),

    ORDER_SENT_TO_COURIER("Courier", "Order sent to courier", ORDER),
    ORDER_COURIER_REFRESHED("Courier", "Courier status refreshed", ORDER),
    ORDERS_SENT_TO_COURIER("Courier", "Orders sent to courier in bulk", ORDERS),
    COURIER_SETTINGS_CHANGED("Courier", "Courier settings changed", COURIER_SETTINGS),
    COURIER_WEBHOOK_TOKEN_CHANGED("Courier", "Courier webhook token changed", COURIER_SETTINGS),

    VARIANT_CREATED("Stock", "Stock item added", VARIANT),
    VARIANTS_CREATED("Stock", "Stock items added in bulk", VARIANTS),
    VARIANT_EDITED("Stock", "Stock updated", VARIANT),
    VARIANTS_EDITED("Stock", "Stock updated in bulk", VARIANTS),
    VARIANT_MERGED("Stock", "Stock items merged", VARIANT),
    VARIANT_DELETED("Stock", "Stock item deleted", VARIANT),
    SIZE_CREATED("Stock", "Size added", SIZE),
    SIZE_EDITED("Stock", "Size edited", SIZE),
    SIZES_REORDERED("Stock", "Sizes reordered", SIZES),
    SIZE_DELETED("Stock", "Size deleted", SIZE),

    EXPENSE_CREATED("Expenses", "Expense added", EXPENSE),
    EXPENSE_EDITED("Expenses", "Expense edited", EXPENSE),
    EXPENSE_DELETED("Expenses", "Expense deleted", EXPENSE),
    EXPENSE_CATEGORY_CREATED("Expenses", "Expense category added", EXPENSE_CATEGORY),
    EXPENSE_CATEGORY_EDITED("Expenses", "Expense category edited", EXPENSE_CATEGORY),
    EXPENSE_CATEGORY_DELETED("Expenses", "Expense category deleted", EXPENSE_CATEGORY),

    DAILY_EXPENSE_CREATED("Expense (Daily)", "Daily expense added", DAILY_EXPENSE),
    DAILY_EXPENSE_CANCELLED("Expense (Daily)", "Daily expense taken back", DAILY_EXPENSE),
    DAILY_EXPENSE_EDITED("Expense (Daily)", "Daily expense edited", DAILY_EXPENSE),
    DAILY_EXPENSE_DELETED("Expense (Daily)", "Daily expense deleted", DAILY_EXPENSE),
    DAILY_EXPENSE_APPROVED("Expense (Daily)", "Daily expense approved", DAILY_EXPENSE),
    DAILY_EXPENSE_REJECTED("Expense (Daily)", "Daily expense rejected", DAILY_EXPENSE),
    DAILY_EXPENSE_PAID("Expense (Daily)", "Daily expense paid updated", DAILY_EXPENSE),
    DAILY_CATEGORY_CREATED("Expense (Daily)", "Daily expense category added", DAILY_CATEGORY),
    DAILY_CATEGORY_EDITED("Expense (Daily)", "Daily expense category edited", DAILY_CATEGORY),
    DAILY_CATEGORY_DELETED("Expense (Daily)", "Daily expense category deleted", DAILY_CATEGORY),

    BOOST_COST_ADDED("Expense (Boost)", "Boost cost added", BOOST_COST),
    BOOST_COST_EDITED("Expense (Boost)", "Boost cost edited", BOOST_COST),
    BOOST_COST_DELETED("Expense (Boost)", "Boost cost deleted", BOOST_COST),
    BOOST_PURCHASE_ADDED("Expense (Boost)", "Dollars bought", BOOST_PURCHASE),
    BOOST_PURCHASE_EDITED("Expense (Boost)", "Dollar purchase edited", BOOST_PURCHASE),
    BOOST_PURCHASE_DELETED("Expense (Boost)", "Dollar purchase deleted", BOOST_PURCHASE),

    ASSET_CREATED("Assets", "Asset added", ASSET),
    ASSET_EDITED("Assets", "Asset edited", ASSET),
    ASSET_STATUS_CHANGED("Assets", "Asset status changed", ASSET),
    ASSET_REPAIR_ADDED("Assets", "Asset repair added", ASSET),
    ASSET_REPAIR_REMOVED("Assets", "Asset repair removed", ASSET),
    ASSET_DELETED("Assets", "Asset deleted", ASSET),
    ASSET_CATEGORY_CREATED("Assets", "Asset category added", ASSET_CATEGORY),
    ASSET_CATEGORY_EDITED("Assets", "Asset category edited", ASSET_CATEGORY),
    ASSET_CATEGORY_DELETED("Assets", "Asset category deleted", ASSET_CATEGORY),

    EMPLOYEE_REGISTERED("Employees", "Employee registered", EMPLOYEE),
    EMPLOYEE_EDITED("Employees", "Employee edited", EMPLOYEE),
    EMPLOYEE_DELETED("Employees", "Employee deleted", EMPLOYEE),

    ATTENDANCE_CHECK_IN("Attendance", "Checked in on time", ATTENDANCE, false, false),
    ATTENDANCE_CHECK_IN_PROBLEM("Attendance", "Checked in late or away from work", ATTENDANCE),
    ATTENDANCE_CHECK_OUT("Attendance", "Checked out on time", ATTENDANCE, false, false),
    ATTENDANCE_CHECK_OUT_PROBLEM("Attendance", "Left early or away from work", ATTENDANCE),
    ATTENDANCE_REVIEWED("Attendance", "Attendance approved", ATTENDANCE),
    ATTENDANCE_CORRECTED("Attendance", "Attendance corrected", ATTENDANCE_PERSON),
    ATTENDANCE_SHIFT_SET("Attendance", "Working hours set", ATTENDANCE_PERSON),
    ATTENDANCE_SHIFT_REMOVED("Attendance", "Working hours removed", ATTENDANCE_PERSON),
    ATTENDANCE_SPECIAL_HOURS_SET("Attendance", "Special hours set", SPECIAL_HOURS),
    ATTENDANCE_SPECIAL_HOURS_DELETED("Attendance", "Special hours deleted", SPECIAL_HOURS),
    ATTENDANCE_LOCATION_CREATED("Attendance", "Location added", LOCATION),
    ATTENDANCE_LOCATION_EDITED("Attendance", "Location edited", LOCATION),
    ATTENDANCE_LOCATION_DELETED("Attendance", "Location deleted", LOCATION),

    STAFF_CREATED("Staff", "Staff member added", STAFF),
    STAFF_EDITED("Staff", "Staff member or permissions edited", STAFF),
    STAFF_ACTIVATED("Staff", "Staff member activated", STAFF),
    STAFF_DEACTIVATED("Staff", "Staff member deactivated", STAFF),
    STAFF_UNLOCKED("Staff", "Staff member unlocked", STAFF),
    STAFF_PASSWORD_RESET("Staff", "Staff password reset", STAFF),
    STAFF_PASSWORD_REQUEST_DISMISSED("Staff", "Forgot-password request dismissed", PERSONAL),
    OWN_PASSWORD_CHANGED("Staff", "Changed their own password", PERSONAL),

    NOTIFICATION_SETUP_CHANGED("Notifications", "Notification setup changed", NOTIFICATIONS);

    private final String section;
    private final String label;
    private final NotificationTarget target;
    private final boolean enabledByDefault;
    private final boolean pushByDefault;

    NotificationAction(String section, String label, NotificationTarget target) {
        this(section, label, target, true, true);
    }

    NotificationAction(String section, String label, NotificationTarget target, boolean enabledByDefault, boolean pushByDefault) {
        this.section = section;
        this.label = label;
        this.target = target;
        this.enabledByDefault = enabledByDefault;
        this.pushByDefault = pushByDefault;
    }

    public String section() {
        return section;
    }

    public String label() {
        return label;
    }

    NotificationTarget target() {
        return target;
    }

    boolean enabledByDefault() {
        return enabledByDefault;
    }

    boolean pushByDefault() {
        return pushByDefault;
    }

    boolean isDeletion() {
        return name().endsWith("_DELETED");
    }
}
