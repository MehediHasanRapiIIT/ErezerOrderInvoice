package com.erezer.admin.customer;

/**
 * Filters for the customer list and export.
 *
 * @param search  matches name, email, address or phone (any formatting)
 * @param segment {@code repeat} (2+ orders), {@code returns} (at least one return) or null for everyone
 * @param sort    {@code lastOrder}, {@code totalPaid}, {@code orders} or {@code name}
 */
public record CustomerQuery(String search, String segment, String sort) {
}
