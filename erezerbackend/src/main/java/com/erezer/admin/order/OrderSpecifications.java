package com.erezer.admin.order;

import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Composable filters for the order list. Each returns {@code null} (no-op) when its input is empty. */
final class OrderSpecifications {

    private OrderSpecifications() {
    }

    static Specification<Order> statusIn(Collection<OrderStatus> statuses) {
        return statuses == null || statuses.isEmpty() ? null : (root, query, cb) -> root.get("status").in(statuses);
    }

    static Specification<Order> paymentMethod(PaymentMethod method) {
        return method == null ? null : (root, query, cb) -> cb.equal(root.get("paymentMethod"), method);
    }

    static Specification<Order> createdFrom(Instant from) {
        return from == null ? null : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    static Specification<Order> createdBefore(Instant before) {
        return before == null ? null : (root, query, cb) -> cb.lessThan(root.get("createdAt"), before);
    }

    /** Matches order number, customer name, phone (in any formatting), email or any line item's product code. */
    static Specification<Order> search(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String pattern = "%" + escapeLike(term.trim().toLowerCase(Locale.ROOT)) + "%";
        String digits = term.replaceAll("[^0-9]", "");
        String phoneDigits = digits.startsWith("880") ? digits.substring(2) : digits;
        return (root, query, cb) -> {
            Subquery<Long> codeMatch = query.subquery(Long.class);
            Root<OrderItem> item = codeMatch.from(OrderItem.class);
            codeMatch.select(item.get("id")).where(
                    cb.equal(item.get("order"), root),
                    cb.like(cb.lower(item.get("productCode")), pattern, '\\'));
            var phoneKeyMatch = phoneDigits.length() >= 3
                    ? cb.like(root.get("customerPhoneKey"), "%" + phoneDigits + "%")
                    : cb.disjunction();
            return cb.or(
                    phoneKeyMatch,
                    cb.like(cb.lower(root.get("orderNumber")), pattern, '\\'),
                    cb.like(cb.lower(root.get("customerName")), pattern, '\\'),
                    cb.like(cb.lower(root.get("customerPhone")), pattern, '\\'),
                    cb.like(cb.lower(root.get("customerEmail")), pattern, '\\'),
                    cb.exists(codeMatch));
        };
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
