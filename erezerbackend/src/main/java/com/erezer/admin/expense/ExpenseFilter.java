package com.erezer.admin.expense;

import com.erezer.admin.order.PaymentMethod;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filters for the expense list, export and totals.
 *
 * @param query matches description, reference, notes or category name
 * @param from  inclusive expense date
 * @param to    inclusive expense date
 */
public record ExpenseFilter(String query, Long categoryId, PaymentMethod paymentMethod, LocalDate from, LocalDate to,
        BigDecimal minAmount, BigDecimal maxAmount) {

    Specification<Expense> toSpecification() {
        return Specification.allOf(Stream.of(search(), category(), payment(), dateFrom(), dateTo(), amountMin(), amountMax())
                .filter(Objects::nonNull)
                .toList());
    }

    private Specification<Expense> search() {
        if (query == null || query.isBlank()) {
            return null;
        }
        String pattern = "%" + query.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return (root, q, cb) -> {
            Join<Expense, ExpenseCategory> category = root.join("category", JoinType.INNER);
            return cb.or(
                    cb.like(cb.lower(root.get("description")), pattern, '\\'),
                    cb.like(cb.lower(root.get("reference")), pattern, '\\'),
                    cb.like(cb.lower(root.get("notes")), pattern, '\\'),
                    cb.like(cb.lower(category.get("name")), pattern, '\\'));
        };
    }

    private Specification<Expense> category() {
        return categoryId == null ? null : (root, q, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    private Specification<Expense> payment() {
        return paymentMethod == null ? null : (root, q, cb) -> cb.equal(root.get("paymentMethod"), paymentMethod);
    }

    private Specification<Expense> dateFrom() {
        return from == null ? null : (root, q, cb) -> cb.greaterThanOrEqualTo(root.get("expenseDate"), from);
    }

    private Specification<Expense> dateTo() {
        return to == null ? null : (root, q, cb) -> cb.lessThanOrEqualTo(root.get("expenseDate"), to);
    }

    private Specification<Expense> amountMin() {
        return minAmount == null ? null : (root, q, cb) -> cb.greaterThanOrEqualTo(root.get("amount"), minAmount);
    }

    private Specification<Expense> amountMax() {
        return maxAmount == null ? null : (root, q, cb) -> cb.lessThanOrEqualTo(root.get("amount"), maxAmount);
    }
}
