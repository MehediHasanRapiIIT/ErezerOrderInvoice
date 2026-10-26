package com.erezer.admin.asset.dto;

import com.erezer.admin.asset.AssetEventKind;
import com.erezer.admin.asset.AssetStatus;
import com.erezer.admin.order.PaymentMethod;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class AssetDtos {

    private AssetDtos() {
    }

    // ---- Requests -------------------------------------------------------------

    /**
     * @param status  only on create (an owned status; default In use); sold / disposed go through the status action
     * @param version required on update for optimistic locking; ignored on create
     */
    public record AssetRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull Long categoryId,
            @Min(1) @Max(100000) int quantity,
            /** Ignored for people who can't see costs (a new asset then costs 0 until someone who can fills it in). */
            @DecimalMin("0") @DecimalMax("99999999") @Digits(integer = 10, fraction = 2) BigDecimal unitCost,
            @NotNull LocalDate purchaseDate,
            @Size(max = 120) String supplier,
            PaymentMethod paymentMethod,
            @Size(max = 80) String reference,
            @Size(max = 80) String location,
            @Size(max = 120) String serialNumber,
            LocalDate warrantyUntil,
            @Size(max = 1000) String notes,
            Long photoFileId,
            Long receiptFileId,
            AssetStatus status,
            Long version) {
    }

    /** @param salePrice required when selling */
    public record StatusRequest(
            @NotNull AssetStatus status,
            @NotNull LocalDate date,
            @DecimalMin("0") @DecimalMax("99999999") @Digits(integer = 10, fraction = 2) BigDecimal salePrice,
            @Size(max = 300) String note) {
    }

    public record RepairRequest(
            @NotNull LocalDate date,
            @NotBlank @Size(max = 400) String description,
            @DecimalMin("0") @DecimalMax("99999999") @Digits(integer = 10, fraction = 2) BigDecimal cost) {
    }

    public record CategoryRequest(@NotBlank @Size(max = 60) String name, Boolean active) {
    }

    // ---- Responses ------------------------------------------------------------

    /** @param events the history, newest first (empty in list rows) */
    public record AssetResponse(
            long id,
            String name,
            CategoryRef category,
            int quantity,
            BigDecimal unitCost,
            BigDecimal totalCost,
            LocalDate purchaseDate,
            String supplier,
            PaymentMethod paymentMethod,
            String reference,
            String location,
            String serialNumber,
            LocalDate warrantyUntil,
            AssetStatus status,
            String statusLabel,
            LocalDate disposedOn,
            BigDecimal salePrice,
            String notes,
            FileRef photo,
            FileRef receipt,
            BigDecimal repairTotal,
            UserRef createdBy,
            Instant createdAt,
            Instant updatedAt,
            long version,
            List<EventView> events) {
    }

    public record EventView(long id, AssetEventKind kind, LocalDate date, String description, BigDecimal cost,
            UserRef createdBy, Instant createdAt) {
    }

    public record CategoryRef(long id, String name) {
    }

    public record FileRef(long fileId, String url, String contentType, String fileName) {
    }

    public record UserRef(long id, String name) {
    }

    /**
     * A page of assets, the value and pieces of everything matching the filters, and the shop-wide summary
     * (independent of the filters).
     */
    public record AssetPage(
            List<AssetResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            BigDecimal filteredValue,
            long filteredItems,
            Summary summary) {
    }

    /**
     * @param ownedValue         cost of everything the shop still owns (not sold / disposed)
     * @param ownedItems         pieces of those
     * @param underRepair        entries under repair
     * @param warrantyEndingSoon owned entries whose warranty ends within 30 days
     * @param repairSpent        everything spent on repairs, all time
     */
    public record Summary(BigDecimal ownedValue, long ownedItems, long ownedEntries, long underRepair,
            long warrantyEndingSoon, BigDecimal repairSpent) {
    }

    public record CategoryResponse(long id, String name, boolean active, long assetCount) {
    }
}
