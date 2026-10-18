package com.erezer.admin.courier;

import com.erezer.admin.order.OrderService;
import com.erezer.admin.order.OrderStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a Steadfast status to the shipment and, where it is unambiguous, to the order:
 * <ul>
 *   <li>{@code delivered} → order Delivered</li>
 *   <li>{@code cancelled} (parcel came back without being delivered) → order Returned</li>
 *   <li>{@code partial_delivered}, {@code hold}, {@code unknown} and anything unexpected → flagged for a person</li>
 *   <li>everything else ({@code in_review}, {@code pending}, {@code *_approval_pending}) → shipment only</li>
 * </ul>
 * The courier's real charge is stored on the order so the dashboard uses actual courier costs. Orders never move
 * backwards; if a transition isn't allowed (e.g. someone changed the order by hand), the shipment is flagged.
 */
@Service
class CourierStatusSync {

    private static final Logger log = LoggerFactory.getLogger(CourierStatusSync.class);

    static final Set<String> FINAL = Set.of("delivered", "partial_delivered", "cancelled");
    private static final Set<String> IN_FLIGHT = Set.of("in_review", "pending", "delivered_approval_pending",
            "partial_delivered_approval_pending", "cancelled_approval_pending", "unknown_approval_pending");

    private final CourierShipmentRepository shipments;
    private final CourierEventRepository events;
    private final OrderService orderService;
    private final Clock clock;

    CourierStatusSync(CourierShipmentRepository shipments, CourierEventRepository events, OrderService orderService,
            Clock clock) {
        this.shipments = shipments;
        this.events = events;
        this.orderService = orderService;
        this.clock = clock;
    }

    record Update(String status, String trackingMessage, BigDecimal deliveryCharge, String consignmentId) {
    }

    /** @return false when the event was a duplicate and nothing was applied */
    @Transactional
    boolean apply(long shipmentId, Update update, CourierEvent.Source source, String dedupeKey, String payload) {
        if (dedupeKey != null && events.existsByDedupeKey(dedupeKey)) {
            return false;
        }
        CourierShipment shipment = shipments.findById(shipmentId).orElseThrow();
        String status = update.status() == null ? null : update.status().trim().toLowerCase();
        events.save(new CourierEvent(shipmentId, source, dedupeKey, status, payload, clock.instant()));

        String previous = shipment.getCourierStatus();
        shipment.fillIdentifiers(update.consignmentId(), null);
        shipment.updateStatus(status, update.trackingMessage(), update.deliveryCharge(), clock.instant());
        if (status == null || status.equals(previous) && update.deliveryCharge() == null) {
            return true;
        }

        String note = "Steadfast: " + status.replace('_', ' ')
                + (update.trackingMessage() == null ? "" : " · " + update.trackingMessage());
        switch (status) {
            case "delivered" -> moveOrder(shipment, OrderStatus.DELIVERED, update.deliveryCharge(), note);
            case "cancelled" -> moveOrder(shipment, OrderStatus.RETURNED, update.deliveryCharge(), note);
            case "partial_delivered" -> {
                orderService.applyCourierOutcome(shipment.getOrderId(), null, update.deliveryCharge(), note);
                shipment.setNeedsAttention(true);
            }
            default -> {
                if (!IN_FLIGHT.contains(status)) {
                    shipment.setNeedsAttention(true); // hold, unknown, or a status we don't know yet
                }
            }
        }
        return true;
    }

    private void moveOrder(CourierShipment shipment, OrderStatus target, BigDecimal courierCharge, String note) {
        boolean moved = orderService.applyCourierOutcome(shipment.getOrderId(), target, courierCharge, note);
        if (!moved) {
            log.info("Order {} could not move to {} from its current status; flagged for review", shipment.getOrderId(), target);
            shipment.setNeedsAttention(true);
        } else {
            shipment.setNeedsAttention(false);
        }
    }
}
