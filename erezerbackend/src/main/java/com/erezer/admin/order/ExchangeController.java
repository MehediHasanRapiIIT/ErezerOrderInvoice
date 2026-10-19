package com.erezer.admin.order;

import com.erezer.admin.order.dto.OrderDtos.ExchangeCandidate;
import com.erezer.admin.order.dto.OrderDtos.ExchangeOptions;
import com.erezer.admin.order.dto.OrderDtos.ExchangeRequest;
import com.erezer.admin.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ExchangeController {

    private final ExchangeService exchanges;

    ExchangeController(ExchangeService exchanges) {
        this.exchanges = exchanges;
    }

    /** Delivered orders by order number or customer phone. */
    @GetMapping("/api/exchanges/lookup")
    @PreAuthorize("hasAnyAuthority('EXCHANGE_CREATE', 'RETURN_CREATE')")
    List<ExchangeCandidate> lookup(@RequestParam String q) {
        return exchanges.lookup(q);
    }

    @GetMapping("/api/orders/{id}/exchange-options")
    @PreAuthorize("hasAuthority('EXCHANGE_CREATE')")
    ExchangeOptions options(@PathVariable long id) {
        return exchanges.options(id, CurrentUser.isAdmin());
    }

    @PostMapping("/api/orders/{id}/exchanges")
    @PreAuthorize("hasAuthority('EXCHANGE_CREATE')")
    ResponseEntity<OrderController.CreatedResponse> create(@PathVariable long id,
            @Valid @RequestBody ExchangeRequest request) {
        long exchangeId = exchanges.create(id, request, CurrentUser.id(), CurrentUser.isAdmin());
        return ResponseEntity.created(URI.create("/api/orders/" + exchangeId))
                .body(new OrderController.CreatedResponse(exchangeId));
    }
}
