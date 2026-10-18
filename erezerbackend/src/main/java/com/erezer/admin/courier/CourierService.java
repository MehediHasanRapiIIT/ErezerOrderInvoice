package com.erezer.admin.courier;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.courier.CourierDtos.CustomerHistory;
import com.erezer.admin.courier.CourierDtos.EventView;
import com.erezer.admin.courier.CourierDtos.ParcelPreview;
import com.erezer.admin.courier.CourierDtos.PreviewResponse;
import com.erezer.admin.courier.CourierDtos.SendResult;
import com.erezer.admin.courier.CourierDtos.ShipmentView;
import com.erezer.admin.courier.ParcelPlanner.Overrides;
import com.erezer.admin.courier.ParcelPlanner.Plan;
import com.erezer.admin.courier.SteadfastClient.Booking;
import com.erezer.admin.courier.SteadfastClient.Credentials;
import com.erezer.admin.customer.CustomerQueryService;
import com.erezer.admin.order.Order;
import com.erezer.admin.order.OrderRepository;
import com.erezer.admin.order.OrderService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends orders to Steadfast. A send is three steps so that a parcel is never booked twice:
 * <ol>
 *   <li>Lock the order, validate it and insert a CONFIRMING shipment (one per order, enforced by a unique index).</li>
 *   <li>Call Steadfast outside any transaction.</li>
 *   <li>Record the consignment and move the order to Shipped, or roll the shipment back if Steadfast certainly
 *       rejected it. If the outcome is unknown (timeout), the shipment stays CONFIRMING and the poller resolves it
 *       by invoice, so a retry by the user cannot create a duplicate parcel.</li>
 * </ol>
 */
@Service
public class CourierService {

    private static final Logger log = LoggerFactory.getLogger(CourierService.class);
    static final String PROVIDER = "STEADFAST";
    static final String COURIER_NAME = "Steadfast";

    private final OrderRepository orders;
    private final OrderService orderService;
    private final CourierShipmentRepository shipments;
    private final CourierEventRepository events;
    private final CourierSettingsService settings;
    private final ParcelPlanner planner;
    private final SteadfastClient client;
    private final CourierStatusSync sync;
    private final CustomerQueryService customers;
    private final TransactionTemplate tx;
    private final Clock clock;

    CourierService(OrderRepository orders, OrderService orderService, CourierShipmentRepository shipments,
            CourierEventRepository events, CourierSettingsService settings, ParcelPlanner planner, SteadfastClient client,
            CourierStatusSync sync, CustomerQueryService customers, PlatformTransactionManager transactionManager,
            Clock clock) {
        this.orders = orders;
        this.orderService = orderService;
        this.shipments = shipments;
        this.events = events;
        this.settings = settings;
        this.planner = planner;
        this.client = client;
        this.sync = sync;
        this.customers = customers;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---- Preview ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PreviewResponse preview(List<Long> orderIds) {
        int deliveryType = settings.availability().defaultDeliveryType();
        Map<Long, CourierShipment> existing = shipments.findByOrderIdIn(orderIds).stream()
                .collect(Collectors.toMap(CourierShipment::getOrderId, Function.identity()));
        Map<Long, Order> found = orders.findAllById(orderIds).stream()
                .collect(Collectors.toMap(Order::getId, Function.identity()));

        List<ParcelPreview> parcels = orderIds.stream().distinct()
                .map(found::get)
                .filter(java.util.Objects::nonNull)
                .map(order -> toPreview(order, planner.plan(order, Overrides.NONE, deliveryType,
                        existing.containsKey(order.getId())), existing.get(order.getId())))
                .toList();
        int ready = (int) parcels.stream().filter(ParcelPreview::ready).count();
        BigDecimal totalCod = parcels.stream().filter(ParcelPreview::ready).map(ParcelPreview::codAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PreviewResponse(parcels, ready, totalCod);
    }

    private ParcelPreview toPreview(Order order, Plan plan, CourierShipment shipment) {
        CustomerHistory history = customers.findByPhone(order.getCustomerPhone())
                .map(c -> new CustomerHistory(c.orders(), c.delivered(), c.returned()))
                .orElse(null);
        var parcel = plan.parcel();
        return new ParcelPreview(order.getId(), order.getOrderNumber(), order.getStatus(), parcel.recipientName(),
                parcel.recipientPhone(), parcel.recipientAddress(), order.getPaymentMethod(), order.getTotal(),
                plan.codAmount(), plan.defaultCod(), parcel.deliveryType(), parcel.note(), parcel.itemDescription(),
                plan.ready(), plan.blockedReason(), plan.problems(), history,
                shipment == null ? null : shipment.getTrackingCode());
    }

    // ---- Send ---------------------------------------------------------------------------------------

    /** Sends one order. Throws {@link ApiException} when it cannot be sent or Steadfast rejects it. */
    public SendResult send(long orderId, Overrides overrides, long userId) {
        Credentials credentials = settings.requireCredentials();
        int defaultDeliveryType = settings.availability().defaultDeliveryType();

        // 1. Lock, validate and reserve (a second click or a concurrent bulk send hits the unique index).
        Reservation reservation;
        try {
            reservation = tx.execute(status -> reserve(orderId, overrides, defaultDeliveryType, userId));
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("ALREADY_SENT", "This order has already been sent to the courier");
        }

        // 2. Call Steadfast outside any transaction.
        try {
            Booking booking = client.createOrder(credentials, reservation.plan().parcel());
            // 3a. Success.
            tx.executeWithoutResult(status -> confirm(reservation.shipmentId(), booking, userId));
            return new SendResult("BOOKED", "Sent to Steadfast", view(reservation.shipmentId()));
        } catch (SteadfastException ex) {
            if (ex.kind() == SteadfastException.Kind.UNAVAILABLE || ex.kind() == SteadfastException.Kind.UNEXPECTED) {
                // 3b. Unknown outcome: keep the reservation; the poller confirms by invoice.
                log.warn("Steadfast outcome unknown for order {}: {}", orderId, ex.getMessage());
                return new SendResult("CONFIRMING",
                        "Steadfast did not answer in time. The booking will be confirmed automatically within a few minutes; "
                                + "don't send this order again.", view(reservation.shipmentId()));
            }
            if (ex.kind() == SteadfastException.Kind.VALIDATION && isDuplicateInvoice(ex.getMessage())) {
                // Steadfast already has a parcel with this invoice (an earlier attempt whose reply was lost, or one
                // booked by hand in the Steadfast panel). Link it rather than release: resending can never work.
                log.warn("Steadfast already has invoice {} (order {}); linking the existing parcel",
                        reservation.plan().parcel().invoice(), orderId);
                CourierShipment shipment = shipments.findById(reservation.shipmentId()).orElseThrow();
                boolean linked = false;
                try {
                    linked = resolve(shipment, credentials, CourierEvent.Source.BOOKING);
                } catch (SteadfastException lookup) {
                    log.warn("Invoice lookup after duplicate failed for order {}: {}", orderId, lookup.getMessage());
                }
                return new SendResult(linked ? "BOOKED" : "CONFIRMING", linked
                        ? "Steadfast already had a parcel for this order; it is now linked here."
                        : "Steadfast already has a parcel for this order. It will be linked automatically within a few minutes.",
                        view(reservation.shipmentId()));
            }
            // 3c. Certainly not booked: release the reservation so the order can be fixed and resent.
            tx.executeWithoutResult(status -> shipments.deleteById(reservation.shipmentId()));
            throw new ApiException(ex.kind() == SteadfastException.Kind.VALIDATION ? HttpStatus.UNPROCESSABLE_ENTITY
                    : HttpStatus.BAD_GATEWAY, "COURIER_" + ex.kind(), ex.getMessage());
        }
    }

    private record Reservation(long shipmentId, Plan plan) {
    }

    /** Steadfast refuses a reused invoice with the code THIS_INVOICE_ALREADY_EXISTS or a Laravel "already been taken". */
    static boolean isDuplicateInvoice(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("invoice_already_exists") || (m.contains("invoice") && (m.contains("already") || m.contains("taken")));
    }

    // ---- Fraud check --------------------------------------------------------------------------------

    /** The customer's delivery record with all Steadfast merchants, shown in the send preview. */
    public SteadfastClient.FraudReport fraudCheck(String rawPhone) {
        String phone = com.erezer.admin.customer.PhoneKeys.normalize(rawPhone == null ? "" : rawPhone);
        if (!phone.matches("01[3-9]\\d{8}")) {
            throw ApiException.badRequest("INVALID_PHONE", "Enter a Bangladeshi mobile number (01XXXXXXXXX)");
        }
        Credentials credentials = settings.requireCredentials();
        try {
            return client.fraudCheck(credentials, phone);
        } catch (SteadfastException ex) {
            if (ex.kind() == SteadfastException.Kind.RATE_LIMITED) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "COURIER_RATE_LIMITED",
                        "Steadfast's fraud-check limit for your account is used up for now. Try again later.");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "COURIER_" + ex.kind(), ex.getMessage());
        }
    }

    private Reservation reserve(long orderId, Overrides overrides, int defaultDeliveryType, long userId) {
        Order order = orders.findForCourierById(orderId).orElseThrow(() -> ApiException.notFound("Order", orderId));
        boolean alreadySent = shipments.findByOrderId(orderId).isPresent();
        Plan plan = planner.plan(order, overrides, defaultDeliveryType, alreadySent);
        if (plan.blockedReason() != null) {
            throw ApiException.conflict(alreadySent ? "ALREADY_SENT" : "NOT_SENDABLE", plan.blockedReason());
        }
        if (!plan.problems().isEmpty()) {
            throw ApiException.badRequest("PARCEL_INVALID", String.join("; ", plan.problems()));
        }
        var parcel = plan.parcel();
        CourierShipment shipment = new CourierShipment(orderId, PROVIDER, parcel.invoice(), plan.codAmount(),
                parcel.deliveryType(), parcel.note(), parcel.itemDescription(), toJson(parcel), userId);
        return new Reservation(shipments.saveAndFlush(shipment).getId(), plan);
    }

    private void confirm(long shipmentId, Booking booking, long userId) {
        CourierShipment shipment = shipments.findById(shipmentId).orElseThrow();
        shipment.markBooked(booking.consignmentId(), booking.trackingCode(), booking.trackingLink(), booking.status(),
                booking.responseJson(), clock.instant());
        events.save(new CourierEvent(shipmentId, CourierEvent.Source.BOOKING, null, booking.status(),
                booking.responseJson(), clock.instant()));
        orderService.markShippedByCourier(shipment.getOrderId(), COURIER_NAME,
                booking.trackingCode() != null ? booking.trackingCode() : booking.consignmentId(),
                "Booked with Steadfast · consignment " + booking.consignmentId(), userId);
    }

    // ---- Status -------------------------------------------------------------------------------------

    /** Fetches the current status from Steadfast now (the "Refresh status" button). */
    public ShipmentView refresh(long orderId) {
        CourierShipment shipment = shipments.findByOrderId(orderId)
                .orElseThrow(() -> ApiException.notFound("Courier shipment for order", orderId));
        Credentials credentials = settings.credentialsForSync();
        if (credentials == null) {
            throw ApiException.conflict("COURIER_DISABLED", "Steadfast credentials are not configured");
        }
        try {
            resolve(shipment, credentials, CourierEvent.Source.MANUAL);
        } catch (SteadfastException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "COURIER_" + ex.kind(), ex.getMessage());
        }
        return view(shipment.getId());
    }

    /**
     * Brings one shipment up to date. CONFIRMING shipments are looked up by invoice; once Steadfast knows the
     * invoice the booking is confirmed (the consignment id then arrives with the next webhook).
     *
     * @return false if a CONFIRMING shipment is still unknown to Steadfast
     */
    boolean resolve(CourierShipment shipment, Credentials credentials, CourierEvent.Source source) {
        if (shipment.getState() == CourierShipment.State.CONFIRMING) {
            Optional<String> status = client.statusByInvoice(credentials, shipment.getInvoice());
            if (status.isEmpty()) {
                return false;
            }
            tx.executeWithoutResult(s -> {
                CourierShipment fresh = shipments.findById(shipment.getId()).orElseThrow();
                fresh.markBookedWithoutReply(status.get(), clock.instant());
                orderService.markShippedByCourier(fresh.getOrderId(), COURIER_NAME, fresh.getInvoice(),
                        "Booked with Steadfast (confirmed by invoice after a timeout)", null);
            });
            return true;
        }
        Optional<String> status = shipment.getConsignmentId() != null
                ? client.statusByConsignment(credentials, shipment.getConsignmentId())
                : client.statusByInvoice(credentials, shipment.getInvoice());
        status.ifPresent(value -> sync.apply(shipment.getId(), new CourierStatusSync.Update(value, null, null, null),
                source, null, "{\"delivery_status\":\"" + value + "\"}"));
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<ShipmentView> shipmentFor(long orderId) {
        return shipments.findByOrderId(orderId).map(s -> view(s.getId()));
    }

    @Transactional(readOnly = true)
    public ShipmentView view(long shipmentId) {
        CourierShipment s = shipments.findById(shipmentId).orElseThrow();
        List<EventView> history = events.findTop20ByShipmentIdOrderByReceivedAtDesc(shipmentId).stream()
                .map(e -> new EventView(e.getSource().name(), e.getCourierStatus(), e.getReceivedAt()))
                .toList();
        return new ShipmentView(s.getProvider(), s.getState().name(), s.getInvoice(), s.getConsignmentId(),
                s.getTrackingCode(), s.getTrackingLink(), s.getCodAmount(), s.getDeliveryType(), s.getNote(),
                s.getItemDescription(), s.getCourierStatus(), s.getCourierStatusAt(), s.getTrackingMessage(),
                s.getDeliveryCharge(), s.isNeedsAttention(), s.getCreatedAt(), history);
    }

    private static String toJson(SteadfastClient.Parcel parcel) {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(parcel.toBody());
    }
}
