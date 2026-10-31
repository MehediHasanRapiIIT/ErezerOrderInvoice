package com.erezer.admin.stock;

import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.notification.NotificationAction;
import com.erezer.admin.notification.Notifies;
import com.erezer.admin.security.CurrentUser;
import com.erezer.admin.stock.dto.StockDtos.BulkCreateRequest;
import com.erezer.admin.stock.dto.StockDtos.BulkUpdateRequest;
import com.erezer.admin.stock.dto.StockDtos.CreateVariantRequest;
import com.erezer.admin.stock.dto.StockDtos.ItemView;
import com.erezer.admin.stock.dto.StockDtos.MergeRequest;
import com.erezer.admin.stock.dto.StockDtos.SizeOrderRequest;
import com.erezer.admin.stock.dto.StockDtos.SizeRequest;
import com.erezer.admin.stock.dto.StockDtos.SizeView;
import com.erezer.admin.stock.dto.StockDtos.MovementView;
import com.erezer.admin.stock.dto.StockDtos.StockSummary;
import com.erezer.admin.stock.dto.StockDtos.UpdateVariantRequest;
import com.erezer.admin.stock.dto.StockDtos.VariantSuggestion;
import com.erezer.admin.stock.dto.StockDtos.VariantView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stock")
class StockController {

    private static final int MAX_PAGE_SIZE = 100;

    private final StockService stock;

    StockController(StockService stock) {
        this.stock = stock;
    }

    /** @param sort {@code name}, {@code quantity} or {@code updatedAt}, with {@code ,asc}/{@code ,desc} */
    @GetMapping("/variants")
    @PreAuthorize("hasAuthority('STOCK_VIEW')")
    PageResponse<VariantView> list(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean low,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        String[] parts = sort.split(",");
        boolean descending = parts.length > 1 && parts[1].equalsIgnoreCase("desc");
        return stock.list(q, low, parts[0], descending, Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    /** For the order form's variant field: names containing the text. */
    @GetMapping("/variants/suggest")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ORDER_EDIT', 'EXCHANGE_CREATE', 'STOCK_VIEW')")
    List<VariantSuggestion> suggest(@RequestParam(defaultValue = "") String q) {
        return stock.suggest(q, 10);
    }

    /** For the order form's size field: the sizes a variant comes in, with how many are left. */
    @GetMapping("/variants/{id}/sizes")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ORDER_EDIT', 'EXCHANGE_CREATE', 'STOCK_VIEW')")
    List<ItemView> sizesOf(@PathVariable long id) {
        return stock.sizesOf(id);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('STOCK_VIEW')")
    StockSummary summary() {
        return stock.summary();
    }

    @Notifies(NotificationAction.VARIANT_CREATED)
    @PostMapping("/variants")
    @PreAuthorize("hasAuthority('STOCK_MANAGE')")
    ResponseEntity<VariantView> create(@Valid @RequestBody CreateVariantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stock.create(request, CurrentUser.id()));
    }

    @Notifies(NotificationAction.VARIANTS_CREATED)
    @PostMapping("/variants/bulk")
    @PreAuthorize("hasAuthority('STOCK_MANAGE')")
    ResponseEntity<List<VariantView>> createAll(@Valid @RequestBody BulkCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stock.createAll(request.items(), CurrentUser.id()));
    }

    @Notifies(NotificationAction.VARIANT_EDITED)
    @PutMapping("/variants/{id}")
    @PreAuthorize("hasAuthority('STOCK_MANAGE')")
    VariantView update(@PathVariable long id, @Valid @RequestBody UpdateVariantRequest request) {
        return stock.update(id, request, CurrentUser.id());
    }

    @Notifies(NotificationAction.VARIANTS_EDITED)
    @PostMapping("/variants/bulk-update")
    @PreAuthorize("hasAuthority('STOCK_MANAGE')")
    List<VariantView> updateAll(@Valid @RequestBody BulkUpdateRequest request) {
        return stock.updateAll(request.items(), request.note(), CurrentUser.id());
    }

    /** Moves a variant into a size of another variant ("Navy Blue - M" → Navy Blue, M). */
    @Notifies(NotificationAction.VARIANT_MERGED)
    @PostMapping("/variants/{id}/merge")
    @PreAuthorize("hasAuthority('STOCK_MANAGE')")
    VariantView merge(@PathVariable long id, @Valid @RequestBody MergeRequest request) {
        return stock.merge(id, request, CurrentUser.id());
    }

    // ---- Sizes: everyone working with stock reads the list; only admins change it ----

    @GetMapping("/sizes")
    @PreAuthorize("hasAnyAuthority('STOCK_VIEW', 'STOCK_MANAGE', 'SIZE_MANAGE')")
    List<SizeView> sizes() {
        return stock.sizes();
    }

    @Notifies(NotificationAction.SIZE_CREATED)
    @PostMapping("/sizes")
    @PreAuthorize("hasAuthority('SIZE_MANAGE')")
    ResponseEntity<List<SizeView>> createSize(@Valid @RequestBody SizeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stock.createSize(request));
    }

    /** Rename and/or switch on or off. */
    @Notifies(NotificationAction.SIZE_EDITED)
    @PutMapping("/sizes/{id}")
    @PreAuthorize("hasAuthority('SIZE_MANAGE')")
    List<SizeView> updateSize(@PathVariable long id, @Valid @RequestBody SizeRequest request) {
        return stock.updateSize(id, request);
    }

    @Notifies(NotificationAction.SIZES_REORDERED)
    @PutMapping("/sizes/order")
    @PreAuthorize("hasAuthority('SIZE_MANAGE')")
    List<SizeView> reorderSizes(@Valid @RequestBody SizeOrderRequest request) {
        return stock.reorderSizes(request.ids());
    }

    @Notifies(NotificationAction.SIZE_DELETED)
    @DeleteMapping("/sizes/{id}")
    @PreAuthorize("hasAuthority('SIZE_MANAGE')")
    List<SizeView> deleteSize(@PathVariable long id) {
        return stock.deleteSize(id);
    }

    @Notifies(NotificationAction.VARIANT_DELETED)
    @DeleteMapping("/variants/{id}")
    @PreAuthorize("hasAuthority('STOCK_MANAGE')")
    ResponseEntity<Void> delete(@PathVariable long id) {
        stock.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/variants/{id}/history")
    @PreAuthorize("hasAuthority('STOCK_VIEW')")
    PageResponse<MovementView> history(@PathVariable long id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return stock.history(id, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }
}
