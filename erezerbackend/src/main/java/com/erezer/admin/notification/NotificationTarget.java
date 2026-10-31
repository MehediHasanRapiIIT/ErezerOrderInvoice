package com.erezer.admin.notification;

/**
 * What an action was done to: how to describe it in a line and where it lives in the panel. Descriptions never include
 * NID numbers, salary accounts, passwords or other private details, because they travel to phones.
 */
public enum NotificationTarget {

    ORDER("select order_number || ' · ' || customer_name || ' · ৳' || total from orders where id = :id", "/orders/{id}"),
    RETURN("""
            select o.order_number || ' · ' || o.customer_name from order_returns r join orders o on o.id = r.order_id
            where r.id = :id""", "/orders"),
    VARIANT("select name from stock_variants where id = :id", "/stock"),
    SIZE("select name from sizes where id = :id", "/stock"),
    EXPENSE("select description || ' · ৳' || amount from expenses where id = :id", "/expenses"),
    EXPENSE_CATEGORY("select name from expense_categories where id = :id", "/expenses"),
    DAILY_EXPENSE("""
            select coalesce(c.name, e.other_category) || ' · ৳' || e.amount || ' · ' || u.full_name
            from daily_expenses e join users u on u.id = e.user_id left join daily_expense_categories c on c.id = e.category_id
            where e.id = :id""", "/daily-expenses"),
    DAILY_CATEGORY("select name from daily_expense_categories where id = :id", "/daily-expenses"),
    BOOST_COST("select '$' || usd || ' at ৳' || rate || ' = ৳' || bdt || coalesce(' · ' || note, '') from boost_costs where id = :id", "/boost"),
    BOOST_PURCHASE("select '$' || usd || ' for ৳' || bdt || coalesce(' · ' || note, '') from boost_purchases where id = :id", "/boost"),
    ASSET("select name from assets where id = :id", "/assets/{id}"),
    ASSET_CATEGORY("select name from asset_categories where id = :id", "/assets"),
    EMPLOYEE("select full_name || ' · ' || employee_code from employees where id = :id", "/employees/{id}"),
    STAFF("select full_name from users where id = :id", "/staff"),
    ATTENDANCE("""
            select u.full_name || ' · ' || r.work_date from attendance_records r join users u on u.id = r.user_id
            where r.id = :id""", "/attendance"),
    ATTENDANCE_PERSON("select full_name from users where id = :id", "/attendance"),
    LOCATION("select name from work_locations where id = :id", "/attendance"),
    SPECIAL_HOURS(null, "/attendance"),
    PERSONAL(null, "/account/password"),
    COURIER_SETTINGS(null, "/settings/courier"),
    ORDERS(null, "/orders"),
    SIZES(null, "/stock"),
    VARIANTS(null, "/stock"),
    NOTIFICATIONS(null, "/notifications");

    private final String sql;
    private final String link;

    NotificationTarget(String sql, String link) {
        this.sql = sql;
        this.link = link;
    }

    /** Null when the action isn't about one record. */
    String sql() {
        return sql;
    }

    /** @param id the record, or null to link to its list */
    String link(Long id, boolean deleted) {
        if (id == null || deleted) {
            return link.replace("/{id}", "");
        }
        return link.replace("{id}", id.toString());
    }
}
