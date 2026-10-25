package com.erezer.admin.order;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.file.FileService;
import com.erezer.admin.file.StoredFile;
import com.erezer.admin.order.dto.OrderDtos.OrderItemRequest;
import com.erezer.admin.order.dto.OrderDtos.OrderRequest;
import com.erezer.admin.order.dto.OrderDtos.StatusChangeRequest;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import com.erezer.admin.stock.StockReason;
import com.erezer.admin.stock.StockService;
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
    private final StockService stock;
    private final ReturnService returns;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    OrderService(OrderRepository orders, OrderNoteRepository notes, FileService files, StockService stock,
            ReturnService returns, Clock clock) {
        this.returns = returns;
        this.stock = stock;
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
        long id = orders.save(order).getId();
        stock.syncOrder(order, StockReason.ORDER_PLACED, userId);
        return id;
    }

    @Transactional
    public void update(long id, OrderRequest request, long userId) {
        Order order = load(id);
        if (request.version() == null || request.version() != order.getVersion()) {
            throw ApiException.conflict("STALE_DATA", "This order was changed by someone else. Reload and try again.");
        }
        if (order.isExchange() && request.discount() != null && request.discount().signum() != 0) {
            // What the rider collects is the price difference; a cheaper replacement is the way to lower it.
            throw ApiException.badRequest("EXCHANGE_DISCOUNT", "Exchange orders have no discount. Lower the new items' price instead.");
        }
        if (!order.isEditable()) {
            throw ApiException.conflict("ORDER_LOCKED", "Cancelled or returned orders can no longer be edited");
        }
        if (orders.hasExchanges(id)) {
            throw ApiException.conflict("ORDER_LOCKED",
                    "This order has exchanges, so its items can no longer be edited");
        }
        String number = request.orderNumber() == null || request.orderNumber().isBlank()
                ? order.getOrderNumber()
                : resolveOrderNumber(request.orderNumber(), id);
        apply(order, number, request);
        // Item-only edits touch child tables alone; marking the order modified makes Hibernate
        // bump its version, so concurrent edits of line items are still detected.
        order.touch(clock.instant());
        stock.syncOrder(order, StockReason.ORDER_CHANGED, userId);
    }

    /**
     * @param free whether the user may change the status freely (any status, any time); otherwise only the normal
     *             forward flow is allowed
     */
    @Transactional
    public void changeStatus(long id, StatusChangeRequest request, long userId, boolean free) {
        Order order = load(id);
        OrderStatus target = request.status();

        if (target == OrderStatus.SHIPPED) {
            // A free move back to Shipped may reuse the courier details the order already has.
            String courier = Objects.requireNonNullElse(blankToNull(request.courier()), free ? Objects.toString(order.getCourier(), "") : "");
            String tracking = Objects.requireNonNullElse(blankToNull(request.trackingNumber()), free ? Objects.toString(order.getTrackingNumber(), "") : "");
            courier = blankToNull(courier);
            tracking = blankToNull(tracking);
            if (courier == null || tracking == null) {
                throw ApiException.badRequest("SHIPMENT_DETAILS_REQUIRED",
                        "Courier and tracking number are required to mark an order as shipped");
            }
            order.assignShipment(courier, tracking);
        }
        if (target == OrderStatus.RETURNED && request.returnDeliveryCharge() != null) {
            order.setReturnDeliveryCharge(request.returnDeliveryCharge());
        }
        OrderStatus before = order.getStatus();
        if (free && !order.getStatus().canMoveTo(target)) {
            String note = blankToNull(request.note());
            order.forceStatus(target, note == null ? "Status changed manually" : "Status changed manually · " + note,
                    userId, clock.instant());
        } else {
            order.changeStatus(target, blankToNull(request.note()), userId, clock.instant());
        }
        // Moved out of Returned (e.g. back to Delivered): the return no longer stands. Cancelling a returned order
        // keeps its return, so the pieces that came back stay out of stock.
        if (before == OrderStatus.RETURNED && target != OrderStatus.RETURNED && target != OrderStatus.CANCELLED) {
            returns.cancelActiveReturns(order);
        }
        if (target == OrderStatus.RETURNED) {
            returns.recordWholeReturn(order, request.returnReason(), request.returnDeliveryCharge(), request.returnPaidBy(), userId);
        }
        stock.syncOrder(order, reasonFor(target), userId);
    }

    private static StockReason reasonFor(OrderStatus status) {
        return switch (status) {
            case CANCELLED -> StockReason.ORDER_CANCELLED;
            case RETURNED -> StockReason.ORDER_RETURNED;
            case DELIVERED -> StockReason.EXCHANGE; // only an exchange moves stock on delivery (old items back)
            default -> StockReason.ORDER_CHANGED;
        };
    }

    /** Courier booked the parcel: the order moves to Shipped with the courier's tracking code. */
    @Transactional
    public void markShippedByCourier(long orderId, String courier, String trackingCode, String note, Long userId) {
        Order order = load(orderId);
        order.assignShipment(courier, trackingCode);
        if (order.getStatus().canMoveTo(OrderStatus.SHIPPED)) {
            order.changeStatus(OrderStatus.SHIPPED, note, userId, clock.instant());
        }
    }

    /**
     * Applies an outcome reported by the courier (delivered / returned). Never moves an order backwards: if the
     * transition is not allowed from the current status, only the courier charge is recorded.
     *
     * @return whether the status changed
     */
    @Transactional
    public boolean applyCourierOutcome(long orderId, OrderStatus target, BigDecimal courierCharge, String note) {
        Order order = load(orderId);
        order.recordCourierCharge(courierCharge);
        if (target == null || !order.getStatus().canMoveTo(target)) {
            return false;
        }
        order.changeStatus(target, note, null, clock.instant());
        if (target == OrderStatus.RETURNED) {
            // The courier reported the parcel back: refused at the door, Erezer pays (staff can change it after).
            returns.recordWholeReturn(order, null, null, DeliveryPaidBy.EREZER, null);
        }
        stock.syncOrder(order, reasonFor(target), null);
        return true;
    }

    /** Records the real courier charge (null clears it) at any time and, for returned orders, the return charge. */
    @Transactional
    public void recordCourierCost(long orderId, BigDecimal courierCharge, BigDecimal returnCharge) {
        Order order = load(orderId);
        if (returnCharge != null && order.getStatus() != OrderStatus.RETURNED) {
            throw ApiException.badRequest("NOT_RETURNED", "A return charge applies only to returned orders");
        }
        order.recordCourierCosts(courierCharge, returnCharge);
    }

    @Transactional
    public void addNote(long orderId, String body, long userId) {
        load(orderId);
        notes.save(new OrderNote(orderId, body.trim(), userId, clock.instant()));
    }

    /** Soft delete: the row stays for audit and so its order number is never reused. */
    @Transactional
    public void delete(long id) {
        Order order = load(id);
        order.markDeleted(clock.instant());
        stock.syncOrder(order, StockReason.ORDER_DELETED, null);
    }

    private void apply(Order order, String number, OrderRequest request) {
        var customer = request.customer();
        order.updateDetails(number, request.paymentMethod(), customer.name().trim(), customer.phone().trim(),
                blankToNull(customer.email()), customer.address().trim(), request.discount(), request.deliveryCharge());
        order.setAdvance(request.advance());

        List<Long> imageIds = request.items().stream()
                .flatMap(i -> i.imageIds() == null ? Stream.<Long>empty() : i.imageIds().stream())
                .toList();
        Map<Long, StoredFile> images = imageIds.isEmpty() ? Map.of() : files.requireImages(imageIds);
        order.replaceItems(request.items().stream().map(i -> toItem(i, images, stock.pick(i.variantId(), i.stockItemId()))).toList());
    }

    static OrderItem toItem(OrderItemRequest request, Map<Long, StoredFile> images, StockService.StockPick pick) {
        List<StoredFile> itemImages = request.imageIds() == null ? List.of()
                : request.imageIds().stream().distinct().map(images::get).toList();
        String code = blankToNull(request.productCode());
        OrderItem item = new OrderItem(request.productName().trim(), code == null ? null : code.toUpperCase(Locale.ROOT),
                blankToNull(request.description()), blankToNull(request.color()), blankToNull(request.size()),
                request.unitPrice(), request.quantity(), itemImages);
        if (pick != null) {
            item.useStock(pick);
        }
        return item;
    }

    String resolveOrderNumber(String requested, long excludeId) {
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
