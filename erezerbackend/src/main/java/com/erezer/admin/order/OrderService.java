package com.erezer.admin.order;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.file.FileService;
import com.erezer.admin.file.StoredFile;
import com.erezer.admin.order.dto.OrderDtos.OrderItemRequest;
import com.erezer.admin.order.dto.OrderDtos.OrderRequest;
import com.erezer.admin.order.dto.OrderDtos.StatusChangeRequest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commands that change orders. Reads live in {@link OrderQueryService}. */
@Service
public class OrderService {

    private static final String NUMBER_PREFIX = "EZ-";
    private static final int NUMBER_ATTEMPTS = 20;

    private final OrderRepository orders;
    private final OrderNoteRepository notes;
    private final FileService files;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    OrderService(OrderRepository orders, OrderNoteRepository notes, FileService files, Clock clock) {
        this.orders = orders;
        this.notes = notes;
        this.files = files;
        this.clock = clock;
    }

    @Transactional
    public long create(OrderRequest request, long userId) {
        String number = resolveOrderNumber(request.orderNumber(), 0L);
        Order order = new Order(number, clock.instant(), userId);
        apply(order, number, request);
        return orders.save(order).getId();
    }

    @Transactional
    public void update(long id, OrderRequest request) {
        Order order = load(id);
        if (request.version() == null || request.version() != order.getVersion()) {
            throw ApiException.conflict("STALE_DATA", "This order was changed by someone else. Reload and try again.");
        }
        if (!order.isEditable()) {
            throw ApiException.conflict("ORDER_LOCKED", "Cancelled or returned orders can no longer be edited");
        }
        String number = request.orderNumber() == null || request.orderNumber().isBlank()
                ? order.getOrderNumber()
                : resolveOrderNumber(request.orderNumber(), id);
        apply(order, number, request);
        // Item-only edits touch child tables alone; marking the order modified makes Hibernate
        // bump its version, so concurrent edits of line items are still detected.
        order.touch(clock.instant());
    }

    @Transactional
    public void changeStatus(long id, StatusChangeRequest request, long userId) {
        Order order = load(id);
        OrderStatus target = request.status();

        if (target == OrderStatus.SHIPPED) {
            String courier = blankToNull(request.courier());
            String tracking = blankToNull(request.trackingNumber());
            if (courier == null || tracking == null) {
                throw ApiException.badRequest("SHIPMENT_DETAILS_REQUIRED",
                        "Courier and tracking number are required to mark an order as shipped");
            }
            order.assignShipment(courier, tracking);
        }
        if (target == OrderStatus.RETURNED && request.returnDeliveryCharge() != null) {
            order.setReturnDeliveryCharge(request.returnDeliveryCharge());
        }
        order.changeStatus(target, blankToNull(request.note()), userId, clock.instant());
    }

    @Transactional
    public void addNote(long orderId, String body, long userId) {
        load(orderId);
        notes.save(new OrderNote(orderId, body.trim(), userId, clock.instant()));
    }

    /** Soft delete: the row stays for audit and so its order number is never reused. */
    @Transactional
    public void delete(long id) {
        load(id).markDeleted(clock.instant());
    }

    private void apply(Order order, String number, OrderRequest request) {
        var customer = request.customer();
        order.updateDetails(number, request.paymentMethod(), customer.name().trim(), customer.phone().trim(),
                blankToNull(customer.email()), customer.address().trim(), request.discount(), request.deliveryCharge());

        List<Long> imageIds = request.items().stream()
                .flatMap(i -> i.imageIds() == null ? Stream.<Long>empty() : i.imageIds().stream())
                .toList();
        Map<Long, StoredFile> images = imageIds.isEmpty() ? Map.of() : files.requireImages(imageIds);

        order.replaceItems(request.items().stream().map(i -> toItem(i, images)).toList());
    }

    private static OrderItem toItem(OrderItemRequest request, Map<Long, StoredFile> images) {
        List<StoredFile> itemImages = request.imageIds() == null ? List.of()
                : request.imageIds().stream().distinct().map(images::get).toList();
        return new OrderItem(request.productName().trim(), request.productCode().trim().toUpperCase(Locale.ROOT),
                blankToNull(request.description()), blankToNull(request.color()), blankToNull(request.size()),
                request.unitPrice(), request.quantity(), itemImages);
    }

    private String resolveOrderNumber(String requested, long excludeId) {
        if (requested != null && !requested.isBlank()) {
            String number = requested.trim().toUpperCase(Locale.ROOT);
            if (orders.orderNumberTaken(number, excludeId)) {
                throw ApiException.conflict("ORDER_NUMBER_TAKEN", "Order number " + number + " is already used");
            }
            return number;
        }
        for (int i = 0; i < NUMBER_ATTEMPTS; i++) {
            String candidate = NUMBER_PREFIX + (100_000 + random.nextInt(900_000));
            if (!orders.orderNumberTaken(candidate, excludeId)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a free order number");
    }

    private Order load(long id) {
        return orders.findById(id).orElseThrow(() -> ApiException.notFound("Order", id));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
