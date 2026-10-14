package com.erezer.admin.order;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.order.OrderQueryService.OrderFilter;
import com.erezer.admin.order.dto.OrderDtos.NoteRequest;
import com.erezer.admin.order.dto.OrderDtos.OrderDetail;
import com.erezer.admin.order.dto.OrderDtos.OrderRequest;
import com.erezer.admin.order.dto.OrderDtos.OrderStats;
import com.erezer.admin.order.dto.OrderDtos.OrderSummary;
import com.erezer.admin.order.dto.OrderDtos.StatusChangeRequest;
import com.erezer.admin.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
@RequestMapping("/api/orders")
class OrderController {

    private static final int MAX_PAGE_SIZE = 100;
    /** API sort keys mapped to entity properties; anything else is rejected. */
    private static final Map<String, String> SORTABLE = Map.of(
            "createdAt", "createdAt",
            "total", "total",
            "orderNumber", "orderNumber",
            "customerName", "customerName");

    private final OrderService orderService;
    private final OrderQueryService queryService;
    private final OrderExportService exportService;
    private final AppProperties appProperties;

    OrderController(OrderService orderService, OrderQueryService queryService, OrderExportService exportService,
            AppProperties appProperties) {
        this.orderService = orderService;
        this.queryService = queryService;
        this.exportService = exportService;
        this.appProperties = appProperties;
    }

    /**
     * @param tab    {@code active} (not yet finished) or {@code history} (delivered, cancelled, returned)
     * @param status narrows further within the tab
     * @param sort   {@code field,direction}, e.g. {@code createdAt,desc}
     */
    @GetMapping
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    PageResponse<OrderSummary> list(
            @RequestParam(required = false) String tab,
            @RequestParam(required = false) List<OrderStatus> status,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        OrderFilter filter = filter(tab, status, paymentMethod, from, to, q);
        if (filter.statuses().isEmpty()) {
            return new PageResponse<>(List.of(), page, size, 0, 0);
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), parseSort(sort));
        return queryService.search(filter, pageable);
    }

    /** Same filters as {@link #list}, as an .xlsx download of every matching order. */
    @GetMapping("/export.xlsx")
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    ResponseEntity<byte[]> export(
            @RequestParam(required = false) String tab,
            @RequestParam(required = false) List<OrderStatus> status,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        byte[] workbook = exportService.export(filter(tab, status, paymentMethod, from, to, q), parseSort(sort));
        String fileName = "orders-" + LocalDate.now(appProperties.zoneId()) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .body(workbook);
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    OrderStats stats() {
        return queryService.stats();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    OrderDetail get(@PathVariable long id) {
        return queryService.detail(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    ResponseEntity<CreatedResponse> create(@Valid @RequestBody OrderRequest request) {
        long id = orderService.create(request, CurrentUser.id());
        return ResponseEntity.created(URI.create("/api/orders/" + id)).body(new CreatedResponse(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ORDER_EDIT')")
    OrderDetail update(@PathVariable long id, @Valid @RequestBody OrderRequest request) {
        orderService.update(id, request);
        return queryService.detail(id);
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('ORDER_STATUS_UPDATE')")
    OrderDetail changeStatus(@PathVariable long id, @Valid @RequestBody StatusChangeRequest request) {
        orderService.changeStatus(id, request, CurrentUser.id());
        return queryService.detail(id);
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize("hasAnyAuthority('ORDER_EDIT', 'ORDER_STATUS_UPDATE')")
    OrderDetail addNote(@PathVariable long id, @Valid @RequestBody NoteRequest request) {
        orderService.addNote(id, request.body(), CurrentUser.id());
        return queryService.detail(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ORDER_DELETE')")
    ResponseEntity<Void> delete(@PathVariable long id) {
        orderService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Combines the tab with an optional status narrowing; an empty status set means "nothing matches". */
    private static OrderFilter filter(String tab, List<OrderStatus> status, PaymentMethod paymentMethod,
            LocalDate from, LocalDate to, String q) {
        Set<OrderStatus> statuses = statusesFor(tab);
        if (status != null && !status.isEmpty()) {
            statuses.retainAll(status);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "The start date must be on or before the end date");
        }
        return new OrderFilter(statuses, paymentMethod, from, to, q);
    }

    private static Set<OrderStatus> statusesFor(String tab) {
        if (tab == null || tab.isBlank() || tab.equalsIgnoreCase("all")) {
            return EnumSet.allOf(OrderStatus.class);
        }
        return switch (tab.toLowerCase()) {
            case "active" -> EnumSet.complementOf(EnumSet.copyOf(OrderStatus.FINISHED));
            case "history" -> EnumSet.copyOf(OrderStatus.FINISHED);
            default -> throw ApiException.badRequest("INVALID_TAB", "tab must be active, history or all");
        };
    }

    private static Sort parseSort(String sort) {
        String[] parts = sort.split(",");
        String property = SORTABLE.get(parts[0].trim());
        if (property == null) {
            throw ApiException.badRequest("INVALID_SORT", "Cannot sort by " + parts[0]);
        }
        boolean ascending = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc");
        Sort primary = ascending ? Sort.by(property).ascending() : Sort.by(property).descending();
        return primary.and(Sort.by("id").descending()); // stable order across pages
    }

    record CreatedResponse(long id) {
    }
}
