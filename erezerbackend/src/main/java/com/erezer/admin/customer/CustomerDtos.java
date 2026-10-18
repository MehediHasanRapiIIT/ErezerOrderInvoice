package com.erezer.admin.customer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CustomerDtos {

    private CustomerDtos() {
    }

    /**
     * One customer, aggregated from their (non-deleted) orders.
     *
     * @param phone     the phone as typed on their most recent order
     * @param phoneKey  normalised phone (digits, without the 880 country code); the customer's identity
     * @param totalPaid Σ order total of delivered orders: what the customer actually paid, incl. delivery
     */
    public record CustomerRow(
            String phoneKey,
            String phone,
            String name,
            String email,
            String address,
            long orders,
            long delivered,
            long returned,
            long cancelled,
            BigDecimal totalPaid,
            Instant firstOrderAt,
            Instant lastOrderAt) {
    }

    /** Counts across all customers, independent of the current filters. */
    public record CustomerSummary(long customers, long repeatCustomers, long customersWithReturns) {
    }

    public record CustomerPage(
            List<CustomerRow> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            CustomerSummary summary) {
    }
}
