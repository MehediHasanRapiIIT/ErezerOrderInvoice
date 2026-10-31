package com.erezer.admin.boost;

import com.erezer.admin.boost.BoostDtos.CostRequest;
import com.erezer.admin.boost.BoostDtos.CostView;
import com.erezer.admin.boost.BoostDtos.PurchaseRequest;
import com.erezer.admin.boost.BoostDtos.PurchaseView;
import com.erezer.admin.boost.BoostDtos.SuggestedRate;
import com.erezer.admin.boost.BoostDtos.Summary;
import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.notification.NotificationAction;
import com.erezer.admin.notification.Notifies;
import com.erezer.admin.security.CurrentUser;
import com.erezer.admin.security.Permission;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/boost")
class BoostController {

    private static final int MAX_PAGE_SIZE = 100;

    private final BoostService boost;

    BoostController(BoostService boost) {
        this.boost = boost;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('BOOST_VIEW')")
    Summary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return boost.summary(from, to, CurrentUser.has(Permission.BOOST_BUY));
    }

    @GetMapping("/rate")
    @PreAuthorize("hasAuthority('BOOST_COST_ADD')")
    SuggestedRate rate() {
        return new SuggestedRate(boost.averageRate());
    }

    @GetMapping("/costs")
    @PreAuthorize("hasAuthority('BOOST_VIEW')")
    PageResponse<CostView> costs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return boost.costs(from, to, q, pageable(page, size, "costDate"));
    }

    @Notifies(NotificationAction.BOOST_COST_ADDED)
    @PostMapping("/costs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('BOOST_COST_ADD')")
    CostView addCost(@Valid @RequestBody CostRequest request) {
        return boost.addCost(request);
    }

    @Notifies(NotificationAction.BOOST_COST_EDITED)
    @PutMapping("/costs/{id}")
    @PreAuthorize("hasAuthority('BOOST_EDIT')")
    CostView updateCost(@PathVariable long id, @Valid @RequestBody CostRequest request) {
        return boost.updateCost(id, request);
    }

    @Notifies(NotificationAction.BOOST_COST_DELETED)
    @DeleteMapping("/costs/{id}")
    @PreAuthorize("hasAuthority('BOOST_DELETE')")
    ResponseEntity<Void> deleteCost(@PathVariable long id) {
        boost.deleteCost(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/purchases")
    @PreAuthorize("hasAuthority('BOOST_BUY')")
    PageResponse<PurchaseView> purchases(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return boost.purchases(from, to, q, pageable(page, size, "purchaseDate"));
    }

    @Notifies(NotificationAction.BOOST_PURCHASE_ADDED)
    @PostMapping("/purchases")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('BOOST_BUY')")
    PurchaseView addPurchase(@Valid @RequestBody PurchaseRequest request) {
        return boost.addPurchase(request);
    }

    @Notifies(NotificationAction.BOOST_PURCHASE_EDITED)
    @PutMapping("/purchases/{id}")
    @PreAuthorize("hasAuthority('BOOST_BUY') and hasAuthority('BOOST_EDIT')")
    PurchaseView updatePurchase(@PathVariable long id, @Valid @RequestBody PurchaseRequest request) {
        return boost.updatePurchase(id, request);
    }

    @Notifies(NotificationAction.BOOST_PURCHASE_DELETED)
    @DeleteMapping("/purchases/{id}")
    @PreAuthorize("hasAuthority('BOOST_BUY') and hasAuthority('BOOST_DELETE')")
    ResponseEntity<Void> deletePurchase(@PathVariable long id) {
        boost.deletePurchase(id);
        return ResponseEntity.noContent().build();
    }

    private static Pageable pageable(int page, int size, String dateField) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc(dateField), Sort.Order.desc("id")));
    }
}
