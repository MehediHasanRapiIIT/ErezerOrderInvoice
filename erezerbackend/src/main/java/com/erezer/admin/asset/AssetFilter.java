package com.erezer.admin.asset;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filters for the asset list and export.
 *
 * @param query    matches name, serial number, supplier, location, notes or category name
 * @param scope    {@code OWNED} (default: not sold / disposed), {@code ALL}, or one status name
 * @param warranty {@code SOON} (owned, ends within 30 days) or {@code EXPIRED}
 * @param today    the business day in Asia/Dhaka, for the warranty filter
 */
public record AssetFilter(String query, Long categoryId, String scope, String location, LocalDate from, LocalDate to,
        String warranty, LocalDate today) {

    static final int WARRANTY_SOON_DAYS = 30;

    Specification<Asset> toSpecification() {
        return Specification.allOf(Stream.of(search(), category(), status(), place(), dateFrom(), dateTo(), warrantyFilter())
                .filter(Objects::nonNull)
                .toList());
    }

    private Specification<Asset> search() {
        if (query == null || query.isBlank()) {
            return null;
        }
        String pattern = "%" + query.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return (root, q, cb) -> {
            Join<Asset, AssetCategory> category = root.join("category", JoinType.INNER);
            return cb.or(
                    cb.like(cb.lower(root.get("name")), pattern, '\\'),
                    cb.like(cb.lower(root.get("serialNumber")), pattern, '\\'),
                    cb.like(cb.lower(root.get("supplier")), pattern, '\\'),
                    cb.like(cb.lower(root.get("location")), pattern, '\\'),
                    cb.like(cb.lower(root.get("notes")), pattern, '\\'),
                    cb.like(cb.lower(category.get("name")), pattern, '\\'));
        };
    }

    private Specification<Asset> category() {
        return categoryId == null ? null : (root, q, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    private Specification<Asset> status() {
        if ("ALL".equals(scope)) {
            return null;
        }
        if (scope == null || scope.isBlank() || "OWNED".equals(scope)) {
            return owned();
        }
        AssetStatus status = AssetStatus.valueOf(scope);
        return (root, q, cb) -> cb.equal(root.get("status"), status);
    }

    private static Specification<Asset> owned() {
        return (root, q, cb) -> root.get("status").in(List.of(AssetStatus.SOLD, AssetStatus.DISPOSED)).not();
    }

    private Specification<Asset> place() {
        return location == null || location.isBlank() ? null
                : (root, q, cb) -> cb.equal(cb.lower(root.get("location")), location.trim().toLowerCase(Locale.ROOT));
    }

    private Specification<Asset> dateFrom() {
        return from == null ? null : (root, q, cb) -> cb.greaterThanOrEqualTo(root.get("purchaseDate"), from);
    }

    private Specification<Asset> dateTo() {
        return to == null ? null : (root, q, cb) -> cb.lessThanOrEqualTo(root.get("purchaseDate"), to);
    }

    private Specification<Asset> warrantyFilter() {
        if ("SOON".equals(warranty)) {
            return Specification.allOf(owned(), (root, q, cb) -> cb.between(root.get("warrantyUntil"), today,
                    today.plusDays(WARRANTY_SOON_DAYS)));
        }
        if ("EXPIRED".equals(warranty)) {
            return (root, q, cb) -> cb.lessThan(root.get("warrantyUntil"), today);
        }
        return null;
    }
}
