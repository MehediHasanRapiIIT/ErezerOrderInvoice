package com.erezer.admin.order;

import com.erezer.admin.order.dto.OrderDtos.OrderDetail;
import com.erezer.admin.order.dto.OrderDtos.ReturnOptions;
import com.erezer.admin.order.dto.OrderDtos.ReturnRequest;
import com.erezer.admin.order.dto.OrderDtos.ReturnUpdateRequest;
import com.erezer.admin.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReturnController {

    private final ReturnService returns;
    private final OrderQueryService queryService;

    ReturnController(ReturnService returns, OrderQueryService queryService) {
        this.returns = returns;
        this.queryService = queryService;
    }

    @GetMapping("/api/orders/{id}/return-options")
    @PreAuthorize("hasAuthority('RETURN_CREATE')")
    ReturnOptions options(@PathVariable long id) {
        return returns.options(id);
    }

    /** Products sent back after delivery; answers with the updated order. */
    @PostMapping("/api/orders/{id}/returns")
    @PreAuthorize("hasAuthority('RETURN_CREATE')")
    ResponseEntity<OrderDetail> create(@PathVariable long id, @Valid @RequestBody ReturnRequest request) {
        returns.create(id, request, CurrentUser.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(queryService.detail(id));
    }

    /** Correct the reason, return charge or who paid it (e.g. after a courier-reported return). */
    @PutMapping("/api/returns/{returnId}")
    @PreAuthorize("hasAnyAuthority('RETURN_CREATE', 'ORDER_STATUS_UPDATE')")
    OrderDetail update(@PathVariable long returnId, @Valid @RequestBody ReturnUpdateRequest request) {
        long orderId = returns.update(returnId, request);
        return queryService.detail(orderId);
    }
}
