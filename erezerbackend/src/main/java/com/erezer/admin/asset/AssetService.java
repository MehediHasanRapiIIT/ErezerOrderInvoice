package com.erezer.admin.asset;

import com.erezer.admin.asset.dto.AssetDtos.AssetRequest;
import com.erezer.admin.asset.dto.AssetDtos.RepairRequest;
import com.erezer.admin.asset.dto.AssetDtos.StatusRequest;
import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.file.FileService;
import com.erezer.admin.file.StoredFile;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commands that change assets. Every change is written to the asset's history. */
@Service
public class AssetService {

    private final AssetRepository assets;
    private final AssetCategoryRepository categories;
    private final AssetEventRepository events;
    private final FileService files;
    private final AppProperties appProperties;
    private final Clock clock;

    AssetService(AssetRepository assets, AssetCategoryRepository categories, AssetEventRepository events,
            FileService files, AppProperties appProperties, Clock clock) {
        this.assets = assets;
        this.categories = categories;
        this.events = events;
        this.files = files;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    @Transactional
    public long create(AssetRequest request, long userId) {
        AssetStatus status = request.status() == null ? AssetStatus.IN_USE : request.status();
        if (!status.owned()) {
            throw ApiException.badRequest("INVALID_STATUS", "Add the asset first, then mark it sold or disposed");
        }
        Asset asset = assets.save(new Asset(details(request, activeCategory(request.categoryId())), status));
        log(asset, AssetEventKind.CREATED, request.purchaseDate(), "Added · " + asset.getQuantity() + " × "
                + taka(asset.getUnitCost()) + " = " + taka(asset.getTotalCost()) + " · " + status.label(), null, userId);
        return asset.getId();
    }

    @Transactional
    public void update(long id, AssetRequest request, long userId) {
        Asset asset = load(id);
        if (request.version() == null || request.version() != asset.getVersion()) {
            throw ApiException.conflict("STALE_DATA", "This asset was changed by someone else. Reload and try again.");
        }
        // Keeping an asset in a since-deactivated category is fine; moving one into it is not.
        AssetCategory category = asset.getCategory().getId().equals(request.categoryId())
                ? asset.getCategory()
                : activeCategory(request.categoryId());
        Asset.Details next = details(request, category);
        String oldLocation = asset.getLocation();
        List<String> changed = changes(asset, next);
        asset.update(next);

        LocalDate today = today();
        if (!Objects.equals(oldLocation, next.location())) {
            log(asset, AssetEventKind.MOVED, today, "Moved from " + orDash(oldLocation) + " to " + orDash(next.location()), null, userId);
        }
        if (!changed.isEmpty()) {
            log(asset, AssetEventKind.UPDATED, today, "Changed " + String.join(", ", changed), null, userId);
        }
    }

    @Transactional
    public void changeStatus(long id, StatusRequest request, long userId) {
        Asset asset = load(id);
        notInFuture(request.date(), "The date");
        if (request.status() == AssetStatus.SOLD && request.salePrice() == null) {
            throw ApiException.badRequest("SALE_PRICE_REQUIRED", "Enter what it was sold for (0 if given away)");
        }
        AssetStatus before = asset.getStatus();
        if (before == request.status()) {
            throw ApiException.badRequest("SAME_STATUS", "The asset is already " + before.label().toLowerCase(Locale.ROOT));
        }
        asset.changeStatus(request.status(), request.date(), request.salePrice());
        asset.touch(clock.instant());
        String text = before.label() + " → " + request.status().label()
                + (request.status() == AssetStatus.SOLD ? " for " + taka(request.salePrice()) : "")
                + (blankToNull(request.note()) == null ? "" : " · " + request.note().trim());
        log(asset, AssetEventKind.STATUS, request.date(), text, null, userId);
    }

    @Transactional
    public void addRepair(long id, RepairRequest request, long userId) {
        Asset asset = load(id);
        notInFuture(request.date(), "The repair date");
        log(asset, AssetEventKind.REPAIR, request.date(), request.description().trim(), request.cost(), userId);
        asset.touch(clock.instant());
    }

    @Transactional
    public void deleteRepair(long id, long eventId) {
        Asset asset = load(id);
        AssetEvent event = events.findById(eventId)
                .filter(e -> e.getAssetId().equals(asset.getId()))
                .orElseThrow(() -> ApiException.notFound("Repair", eventId));
        if (event.getKind() != AssetEventKind.REPAIR) {
            throw ApiException.badRequest("NOT_A_REPAIR", "Only repair records can be removed; the rest of the history stays");
        }
        events.delete(event);
        asset.touch(clock.instant());
    }

    /** Soft delete, so the record stays available for audits. */
    @Transactional
    public void delete(long id) {
        load(id).markDeleted(clock.instant());
    }

    private Asset.Details details(AssetRequest r, AssetCategory category) {
        notInFuture(r.purchaseDate(), "The purchase date");
        if (r.warrantyUntil() != null && r.warrantyUntil().isBefore(r.purchaseDate())) {
            throw ApiException.badRequest("INVALID_WARRANTY", "The warranty can't end before the purchase date");
        }
        return new Asset.Details(r.name().trim().replaceAll("\\s+", " "), category, r.quantity(), r.unitCost(),
                r.purchaseDate(), blankToNull(r.supplier()), r.paymentMethod(), blankToNull(r.reference()),
                blankToNull(r.location()) == null ? null : r.location().trim().replaceAll("\\s+", " "),
                blankToNull(r.serialNumber()), r.warrantyUntil(), blankToNull(r.notes()), photo(r.photoFileId()),
                r.receiptFileId() == null ? null : files.requireReceipt(r.receiptFileId()));
    }

    /** Names of the fields an edit changes (location is logged on its own as a move). */
    private static List<String> changes(Asset a, Asset.Details d) {
        List<String> changed = new ArrayList<>();
        if (!a.getName().equals(d.name())) {
            changed.add("name (" + a.getName() + " → " + d.name() + ")");
        }
        if (!a.getCategory().getId().equals(d.category().getId())) {
            changed.add("category (" + a.getCategory().getName() + " → " + d.category().getName() + ")");
        }
        if (a.getQuantity() != d.quantity()) {
            changed.add("quantity (" + a.getQuantity() + " → " + d.quantity() + ")");
        }
        if (a.getUnitCost().compareTo(d.unitCost()) != 0) {
            changed.add("unit cost (" + taka(a.getUnitCost()) + " → " + taka(d.unitCost()) + ")");
        }
        if (!a.getPurchaseDate().equals(d.purchaseDate())) {
            changed.add("purchase date");
        }
        if (!Objects.equals(a.getSupplier(), d.supplier())) {
            changed.add("supplier");
        }
        if (a.getPaymentMethod() != d.paymentMethod() || !Objects.equals(a.getReference(), d.reference())) {
            changed.add("payment");
        }
        if (!Objects.equals(a.getSerialNumber(), d.serialNumber())) {
            changed.add("serial number");
        }
        if (!Objects.equals(a.getWarrantyUntil(), d.warrantyUntil())) {
            changed.add("warranty");
        }
        if (!Objects.equals(a.getNotes(), d.notes())) {
            changed.add("notes");
        }
        if (!Objects.equals(fileId(a.getPhoto()), fileId(d.photo()))) {
            changed.add("photo");
        }
        if (!Objects.equals(fileId(a.getReceipt()), fileId(d.receipt()))) {
            changed.add("receipt");
        }
        return changed;
    }

    private void log(Asset asset, AssetEventKind kind, LocalDate date, String text, BigDecimal cost, long userId) {
        events.save(new AssetEvent(asset.getId(), kind, date, text, cost, userId, clock.instant()));
    }

    private Asset load(long id) {
        return assets.findById(id).orElseThrow(() -> ApiException.notFound("Asset", id));
    }

    private AssetCategory activeCategory(long id) {
        AssetCategory category = categories.findById(id)
                .orElseThrow(() -> ApiException.badRequest("INVALID_CATEGORY", "The selected category does not exist"));
        if (!category.isActive()) {
            throw ApiException.badRequest("INACTIVE_CATEGORY", "Category " + category.getName() + " is no longer in use");
        }
        return category;
    }

    private StoredFile photo(Long fileId) {
        return fileId == null ? null : files.requireImages(List.of(fileId)).get(fileId);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(appProperties.zoneId()));
    }

    /** "Today" is the business day in Asia/Dhaka, not the server's UTC date. */
    private void notInFuture(LocalDate date, String what) {
        if (date.isAfter(today())) {
            throw ApiException.badRequest("FUTURE_DATE", what + " cannot be in the future");
        }
    }

    private static Long fileId(StoredFile file) {
        return file == null ? null : file.getId();
    }

    private static String orDash(String value) {
        return value == null ? "—" : value;
    }

    static String taka(BigDecimal amount) {
        return "৳" + new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
                .format(amount == null ? BigDecimal.ZERO : amount);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
