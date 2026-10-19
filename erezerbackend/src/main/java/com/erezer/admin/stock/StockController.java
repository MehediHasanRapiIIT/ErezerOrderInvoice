package com.erezer.admin.stock;

import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.security.CurrentUser;
import com.erezer.admin.stock.dto.StockDtos.BulkCreateRequest;
import com.erezer.admin.stock.dto.StockDtos.BulkUpdateRequest;
import com.erezer.admin.stock.dto.StockDtos.CreateVariantRequest;
import com.erezer.admin.stock.dto.StockDtos.MovementView;
import com.erezer.admin.stock.dto.StockDtos.StockSummary;
import com.erezer.admin.stock.dto.StockDtos.UpdateVariantRequest;
import com.erezer.admin.stock.dto.StockDtos.VariantSuggestion;
import com.erezer.admin.stock.dto.StockDtos.VariantView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
        String property = switch (parts[0]) {
            case "quantity" -> "quantity";
            case "updatedAt" -> "updatedAt";
            default -> "name";
        };
        Sort order = parts.length > 1 && parts[1].equalsIgnoreCase("desc") ? Sort.by(property).descending() : Sort.by(property);
        return stock.list(q, low, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), order.and(Sort.by("id"))));
    }

    /** For the order form's variant field: names containing the text. */
    @GetMapping("/variants/suggest")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ORDER_EDIT', 'EXCHANGE_CREATE', 'STOCK_VIEW')")
    List<VariantSuggestion> suggest(@RequestParam(defaultValue = "") String q) {
        return stock.suggest(q, 10);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('STOCK_VIEW')")
    StockSummary summary() {
        return stock.summary();
    }

    @PostMapping("/variants")
    @PreAuthorize("hasAuthority('STOCK_MANAGE')")
    ResponseEntity<VariantView> create(@Valid @RequestBody CreateVariantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stock.create(request, CurrentUser.id()));
    }

    @PostMapping("/variants/bulk")
    @PreAuthorize("hasAuthority('STOCK_MANAGE')")
    ResponseEntity<List<VariantView>> createAll(@Valid @RequestBody BulkCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stock.createAll(request.items(), CurrentUser.id()));
    }

    @PutMapping("/variants/{id}")
    @PreAuthorize("hasAuthority('STOCK_MANAGE')")
    VariantView update(@PathVariable long id, @Valid @RequestBody UpdateVariantRequest request) {
        return stock.update(id, request, CurrentUser.id());
    }

    @PostMapping("/variants/bulk-update")
    @PreAuthorize("hasAuthority('STOCK_MANAGE')")
    List<VariantView> updateAll(@Valid @RequestBody BulkUpdateRequest request) {
        return stock.updateAll(request.items(), request.note(), CurrentUser.id());
    }

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
