package com.erezer.admin.asset;

import com.erezer.admin.asset.dto.AssetDtos.AssetPage;
import com.erezer.admin.asset.dto.AssetDtos.AssetResponse;
import com.erezer.admin.asset.dto.AssetDtos.EventView;
import com.erezer.admin.asset.dto.AssetDtos.Summary;
import com.erezer.admin.security.CurrentUser;
import com.erezer.admin.security.Permission;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Trims asset answers to what the current user may see: without "See costs and prices" every amount is left out
 * (including amounts written in history lines); without "View history" only the repair records remain (they have
 * their own panel).
 */
final class AssetVisibility {

    /** "৳2,500", "৳10,000.50"… */
    private static final Pattern AMOUNT = Pattern.compile("৳[0-9][0-9,]*(\\.[0-9]+)?");

    private AssetVisibility() {
    }

    static boolean costs() {
        return CurrentUser.has(Permission.ASSET_COSTS_VIEW);
    }

    static boolean history() {
        return CurrentUser.has(Permission.ASSET_HISTORY_VIEW);
    }

    static AssetResponse trim(AssetResponse a) {
        boolean costs = costs();
        boolean history = history();
        if (costs && history) {
            return a;
        }
        List<EventView> events = a.events().stream()
                .filter(e -> history || e.kind() == AssetEventKind.REPAIR)
                .map(e -> costs ? e : new EventView(e.id(), e.kind(), e.date(), hideAmounts(e.description()), null,
                        e.createdBy(), e.createdAt()))
                .toList();
        return new AssetResponse(a.id(), a.name(), a.category(), a.quantity(),
                costs ? a.unitCost() : null, costs ? a.totalCost() : null, a.purchaseDate(), a.supplier(),
                a.paymentMethod(), a.reference(), a.location(), a.serialNumber(), a.warrantyUntil(), a.status(),
                a.statusLabel(), a.disposedOn(), costs ? a.salePrice() : null, a.notes(), a.photo(),
                costs ? a.receipt() : null, costs ? a.repairTotal() : null, a.createdBy(), a.createdAt(), a.updatedAt(),
                a.version(), events);
    }

    static AssetPage trim(AssetPage page) {
        if (costs() && history()) {
            return page;
        }
        boolean costs = costs();
        Summary s = page.summary();
        return new AssetPage(page.content().stream().map(AssetVisibility::trim).toList(), page.page(), page.size(),
                page.totalElements(), page.totalPages(), costs ? page.filteredValue() : null, page.filteredItems(),
                new Summary(costs ? s.ownedValue() : null, s.ownedItems(), s.ownedEntries(), s.underRepair(),
                        s.warrantyEndingSoon(), costs ? s.repairSpent() : null));
    }

    private static String hideAmounts(String text) {
        return AMOUNT.matcher(text).replaceAll("৳ •••");
    }
}
