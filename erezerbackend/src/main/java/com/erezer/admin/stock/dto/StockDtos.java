package com.erezer.admin.stock.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class StockDtos {

    private StockDtos() {
    }

    public static final int MAX_QUANTITY = 1_000_000;

    // ---- Requests ----

    public record CreateVariantRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull @Min(0) @Max(MAX_QUANTITY) Integer quantity) {
    }

    public record BulkCreateRequest(@NotEmpty @Size(max = 200) List<@Valid CreateVariantRequest> items) {
    }

    /** Rename and/or set the quantity of one variant. */
    public record UpdateVariantRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull @Min(0) @Max(MAX_QUANTITY) Integer quantity,
            @Size(max = 200) String note,
            Long version) {
    }

    public enum Mode {
        /** Quantity becomes the amount. */
        SET,
        /** Amount is added (new pieces arrived). */
        ADD,
        /** Amount is taken away (damaged, used, counted short). */
        REMOVE
    }

    public record BulkUpdateItem(@NotNull Long id, @NotNull Mode mode, @NotNull @Min(0) @Max(MAX_QUANTITY) Integer amount) {
    }

    public record BulkUpdateRequest(@NotEmpty @Size(max = 500) List<@Valid BulkUpdateItem> items, @Size(max = 200) String note) {
    }

    // ---- Responses ----

    public record VariantView(long id, String name, int quantity, boolean low, Instant updatedAt, long version) {
    }

    /** For the variant field of the order form. */
    public record VariantSuggestion(long id, String name, int quantity) {
    }

    public record MovementView(long id, int change, int quantityAfter, String reason, String reasonLabel, Long orderId,
            String orderNumber, String note, String by, Instant createdAt) {
    }

    public record StockSummary(long variants, long units, long lowStock, int lowStockThreshold) {
    }
}
