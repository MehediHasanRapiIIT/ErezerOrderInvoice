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

    /** How many pieces of one size. */
    public record SizeQuantity(@NotNull Long sizeId, @NotNull @Min(0) @Max(MAX_QUANTITY) Integer quantity) {
    }

    /**
     * @param sizes    the sizes it comes in, with quantities (the panel always sends these)
     * @param quantity without sizes: pieces of unknown size ("No size")
     */
    public record CreateVariantRequest(
            @NotBlank @Size(max = 80) String name,
            @Min(0) @Max(MAX_QUANTITY) Integer quantity,
            @Size(max = 60) List<@Valid SizeQuantity> sizes) {
    }

    public record BulkCreateRequest(@NotEmpty @Size(max = 200) List<@Valid CreateVariantRequest> items) {
    }

    /**
     * Rename and set each size's quantity. Listed sizes are set (and added if new); a size left out is taken off the
     * variant, which needs its quantity to be 0. {@code quantity} alone works for a variant with a single size.
     */
    public record UpdateVariantRequest(
            @NotBlank @Size(max = 80) String name,
            @Min(0) @Max(MAX_QUANTITY) Integer quantity,
            @Size(max = 60) List<@Valid SizeQuantity> sizes,
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

    /** One variant + size ({@code stockItemId}), or a variant ({@code id}) that has a single size. */
    public record BulkUpdateItem(Long id, Long stockItemId, @NotNull Mode mode,
            @NotNull @Min(0) @Max(MAX_QUANTITY) Integer amount) {
    }

    public record BulkUpdateRequest(@NotEmpty @Size(max = 500) List<@Valid BulkUpdateItem> items, @Size(max = 200) String note) {
    }

    /** Move a variant (e.g. "Navy Blue - M") into another variant's size (Navy Blue, M). */
    public record MergeRequest(@NotNull Long targetVariantId, @NotNull Long sizeId) {
    }

    public record SizeRequest(@NotBlank @Size(max = 20) String name, Boolean active) {
    }

    public record SizeOrderRequest(@NotEmpty @Size(max = 200) List<@NotNull Long> ids) {
    }

    // ---- Responses ----

    /** One size of a variant. {@code legacy} = "No size" (stock counted before sizes existed). */
    public record ItemView(long stockItemId, long sizeId, String size, boolean legacy, int quantity, boolean low) {
    }

    public record VariantView(long id, String name, int quantity, boolean low, List<ItemView> sizes, Instant updatedAt,
            long version) {
    }

    /** For the variant and size fields of the order form. */
    public record VariantSuggestion(long id, String name, int quantity, List<ItemView> sizes) {
    }

    public record MovementView(long id, int change, int quantityAfter, String size, String reason, String reasonLabel,
            Long orderId, String orderNumber, String note, String by, Instant createdAt) {
    }

    /** @param lowStock variant + size combinations at or below the threshold */
    public record StockSummary(long variants, long units, long lowStock, int lowStockThreshold) {
    }

    /** @param variants variants carrying the size; @param units pieces in stock in that size */
    public record SizeView(long id, String name, int position, boolean active, long variants, long units) {
    }
}
