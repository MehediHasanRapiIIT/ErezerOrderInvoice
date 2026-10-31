package com.erezer.admin.reject;

import com.erezer.admin.reject.Reject.Kind;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;

public final class RejectDtos {

    private RejectDtos() {
    }

    /**
     * @param variantId null means "Other", and {@code otherName} says what it was
     * @param size      only for "Other"; a stock variant's size comes from {@code stockItemId}
     * @param version   only on edit: the version the form loaded
     */
    public record RejectRequest(
            @NotNull LocalDate date,
            Long variantId,
            Long stockItemId,
            @Size(max = 120) String otherName,
            @Size(max = 30) String size,
            @NotNull Kind kind,
            @Min(1) @Max(100000) int quantity,
            @Size(max = 300) String note,
            Long version) {
    }

    public record RejectView(long id, LocalDate date, boolean fromStock, Long variantId, Long stockItemId, String variantName,
            String size, Kind kind, int quantity, String note, String addedBy, Instant createdAt, long version) {
    }

    /** The cards above the list, in pieces. They follow the dates and search, but not the kind or source filters. */
    public record Summary(long total, long entries, long solid, long printed, long fromStock, long other) {
    }
}
