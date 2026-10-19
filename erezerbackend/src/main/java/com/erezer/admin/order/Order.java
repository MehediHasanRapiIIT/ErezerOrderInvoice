package com.erezer.admin.order;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.common.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hibernate.annotations.SQLRestriction;
import org.springframework.data.annotation.CreatedBy;

@Entity
@Table(name = "orders")
@SQLRestriction("deleted_at is null")
public class Order extends AuditableEntity {

    @Column(name = "order_number", nullable = false, length = 30)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OrderStatus status = OrderStatus.PLACED;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "customer_name", nullable = false, length = 120)
    private String customerName;

    @Column(name = "customer_phone", nullable = false, length = 30)
    private String customerPhone;

    /** Normalised phone computed by the database (digits only, without the 880 country code); read-only. */
    @Column(name = "customer_phone_key", insertable = false, updatable = false, length = 30)
    private String customerPhoneKey;

    @Column(name = "customer_email", length = 160)
    private String customerEmail;

    @Column(name = "customer_address", nullable = false, length = 500)
    private String customerAddress;

    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(name = "delivery_charge", nullable = false, precision = 12, scale = 2)
    private BigDecimal deliveryCharge = BigDecimal.ZERO;

    @Column(name = "total", nullable = false, precision = 12, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    /** Paid before delivery (e.g. by bKash); the rest is due. */
    @Column(name = "advance_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal advanceAmount = BigDecimal.ZERO;

    /** What the courier charged to bring a returned parcel back. */
    @Column(name = "return_delivery_charge", nullable = false, precision = 12, scale = 2)
    private BigDecimal returnDeliveryCharge = BigDecimal.ZERO;

    @Column(name = "courier", length = 80)
    private String courier;

    @Column(name = "tracking_number", length = 80)
    private String trackingNumber;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** False for orders placed before stock existed: they never move stock. */
    @Column(name = "stock_tracked", nullable = false, updatable = false)
    private boolean stockTracked = true;

    /** What the courier actually charged for this parcel (reported by the courier); null when unknown. */
    @Column(name = "courier_charge", precision = 12, scale = 2)
    private BigDecimal courierCharge;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 20, updatable = false)
    private OrderType orderType = OrderType.SALE;

    // ---- Exchange orders only (see V8__order_exchanges.sql) ----

    /** The delivered order whose items this exchange replaces. */
    @Column(name = "exchange_of_order_id", updatable = false)
    private Long exchangeOfOrderId;

    /** Value of the returned items: what the customer already paid for them. */
    @Column(name = "exchange_credit", precision = 12, scale = 2, updatable = false)
    private BigDecimal exchangeCredit;

    /** When the replacement is cheaper: whether the difference is handed back to the customer. */
    @Column(name = "exchange_refund", nullable = false, updatable = false)
    private boolean exchangeRefund;

    @Enumerated(EnumType.STRING)
    @Column(name = "exchange_delivery_paid_by", length = 10, updatable = false)
    private DeliveryPaidBy exchangeDeliveryPaidBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "exchange_reason", length = 30, updatable = false)
    private ExchangeReason exchangeReason;

    @Column(name = "exchange_returned_summary", length = 500, updatable = false)
    private String exchangeReturnedSummary;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("changedAt, id")
    private List<OrderStatusChange> statusHistory = new ArrayList<>();

    protected Order() {
        // for JPA
    }

    Order(String orderNumber, Instant now, Long createdBy) {
        this.orderNumber = orderNumber;
        this.statusHistory.add(new OrderStatusChange(this, null, OrderStatus.PLACED, null, createdBy, now));
    }

    /** An exchange for {@code original}: same customer, paid cash on delivery, accepted straight away. */
    static Order exchange(String orderNumber, Order original, BigDecimal credit, boolean refund, DeliveryPaidBy paidBy,
            ExchangeReason reason, String returnedSummary, BigDecimal deliveryCharge, Instant now, Long createdBy) {
        Order order = new Order(orderNumber, now, createdBy);
        order.orderType = OrderType.EXCHANGE;
        order.exchangeOfOrderId = original.getId();
        order.exchangeCredit = money(credit);
        order.exchangeRefund = refund;
        order.exchangeDeliveryPaidBy = paidBy;
        order.exchangeReason = reason;
        order.exchangeReturnedSummary = returnedSummary;
        order.updateDetails(orderNumber, PaymentMethod.CASH, original.customerName, original.customerPhone,
                original.customerEmail, original.customerAddress, BigDecimal.ZERO, deliveryCharge);
        order.changeStatus(OrderStatus.ACCEPTED, "Exchange of " + original.orderNumber, createdBy, now);
        return order;
    }

    void updateDetails(String orderNumber, PaymentMethod paymentMethod, String customerName, String customerPhone,
            String customerEmail, String customerAddress, BigDecimal discount, BigDecimal deliveryCharge) {
        this.orderNumber = orderNumber;
        this.paymentMethod = paymentMethod;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.customerEmail = customerEmail;
        this.customerAddress = customerAddress;
        this.discount = money(discount);
        this.deliveryCharge = money(deliveryCharge);
    }

    void setAdvance(BigDecimal advance) {
        this.advanceAmount = money(advance);
    }

    /** Replaces all line items; images of removed items become orphans and are purged later. */
    void replaceItems(List<OrderItem> newItems) {
        items.clear();
        for (int i = 0; i < newItems.size(); i++) {
            OrderItem item = newItems.get(i);
            item.attachTo(this, i);
            items.add(item);
        }
        recalculate();
    }

    private void recalculate() {
        subtotal = items.stream().map(OrderItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (orderType == OrderType.EXCHANGE) {
            // The rider collects only a positive price difference; a cheaper replacement is refunded separately.
            BigDecimal difference = subtotal.subtract(exchangeCredit).max(BigDecimal.ZERO);
            total = difference.add(exchangeDeliveryPaidBy == DeliveryPaidBy.CUSTOMER ? deliveryCharge : BigDecimal.ZERO);
            return;
        }
        if (discount.compareTo(subtotal) > 0) {
            throw ApiException.badRequest("DISCOUNT_TOO_LARGE", "Discount cannot exceed the product subtotal");
        }
        total = subtotal.subtract(discount).add(deliveryCharge);
        if (advanceAmount.compareTo(total) > 0) {
            throw ApiException.badRequest("ADVANCE_TOO_LARGE", "The advance cannot be more than the order total");
        }
    }

    void changeStatus(OrderStatus target, String note, Long changedBy, Instant now) {
        if (!status.canMoveTo(target)) {
            throw ApiException.conflict("INVALID_STATUS_TRANSITION",
                    "An order that is " + label(status) + " cannot be moved to " + label(target));
        }
        statusHistory.add(new OrderStatusChange(this, status, target, note, changedBy, now));
        status = target;
        switch (target) {
            case DELIVERED -> deliveredAt = now;
            case RETURNED -> returnedAt = now;
            case CANCELLED -> cancelledAt = now;
            default -> {
            }
        }
    }

    /**
     * Moves the order to any status, outside the normal flow (staff with the "change status freely" permission).
     * The delivered/returned/cancelled dates are made consistent with the new status, so the dashboard counts it
     * correctly: back before delivery → no longer a sale; delivered again after a mistaken return → the original
     * delivery date is kept.
     */
    void forceStatus(OrderStatus target, String note, Long changedBy, Instant now) {
        if (target == status) {
            throw ApiException.badRequest("SAME_STATUS", "The order is already " + label(status));
        }
        if (target == OrderStatus.IN_PRODUCTION) {
            throw ApiException.badRequest("INVALID_STATUS", "This status is no longer used");
        }
        statusHistory.add(new OrderStatusChange(this, status, target, note, changedBy, now));
        status = target;
        switch (target) {
            case DELIVERED -> {
                deliveredAt = deliveredAt != null ? deliveredAt : now;
                returnedAt = null;
                cancelledAt = null;
            }
            case RETURNED -> {
                returnedAt = now;
                cancelledAt = null;
            }
            case CANCELLED -> {
                cancelledAt = now;
                deliveredAt = null;
                returnedAt = null;
            }
            default -> {
                deliveredAt = null;
                returnedAt = null;
                cancelledAt = null;
            }
        }
    }

    void assignShipment(String courier, String trackingNumber) {
        this.courier = courier;
        this.trackingNumber = trackingNumber;
    }

    void setReturnDeliveryCharge(BigDecimal amount) {
        this.returnDeliveryCharge = money(amount);
    }

    void recordCourierCharge(BigDecimal amount) {
        if (amount != null) {
            this.courierCharge = money(amount);
        }
    }

    /**
     * Staff record what the courier really charged (null = unknown, the delivery charge is used as an estimate)
     * and, for returned orders, the return charge.
     */
    void recordCourierCosts(BigDecimal charge, BigDecimal returnCharge) {
        this.courierCharge = charge == null ? null : money(charge);
        if (returnCharge != null) {
            this.returnDeliveryCharge = money(returnCharge);
        }
    }

    /** Whether the parcel went to a courier (so a courier cost is expected). */
    public boolean hasCourierCost() {
        return status.isDispatched();
    }

    /**
     * Everything the courier is paid for this order, by the same rules as the dashboard: the courier's real charge
     * when known (otherwise the delivery charge), plus the return charge when it came back after delivery. For a
     * parcel refused at the door the courier's charge already covers both ways; unknown, it is delivery + return.
     * Null while the order has not been shipped.
     */
    public BigDecimal getTotalCourierCost() {
        if (!hasCourierCost()) {
            return courierCharge; // recorded ahead of shipping, or nothing yet
        }
        if (status == OrderStatus.RETURNED && deliveredAt == null) {
            return courierCharge != null ? courierCharge : deliveryCharge.add(returnDeliveryCharge);
        }
        BigDecimal delivery = courierCharge != null ? courierCharge : deliveryCharge;
        return status == OrderStatus.RETURNED ? delivery.add(returnDeliveryCharge) : delivery;
    }

    void markDeleted(Instant now) {
        this.deletedAt = now;
    }

    public boolean isEditable() {
        return orderType == OrderType.SALE && status != OrderStatus.CANCELLED && status != OrderStatus.RETURNED;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isStockTracked() {
        return stockTracked;
    }

    public boolean isExchange() {
        return orderType == OrderType.EXCHANGE;
    }

    /** Price of the replacement minus the returned items' value; negative when the replacement is cheaper. */
    public BigDecimal getExchangePriceDifference() {
        return isExchange() ? subtotal.subtract(exchangeCredit) : null;
    }

    /** Money handed back to the customer (cheaper replacement with a refund chosen), otherwise zero. */
    public BigDecimal getExchangeRefundAmount() {
        BigDecimal difference = getExchangePriceDifference();
        return difference != null && exchangeRefund && difference.signum() < 0 ? difference.negate() : BigDecimal.ZERO;
    }

    static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String label(OrderStatus status) {
        return status.name().toLowerCase().replace('_', ' ');
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public String getCustomerAddress() {
        return customerAddress;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public BigDecimal getDeliveryCharge() {
        return deliveryCharge;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public BigDecimal getAdvanceAmount() {
        return advanceAmount;
    }

    /** Still to be collected: total − advance. */
    public BigDecimal getDueAmount() {
        return total.subtract(advanceAmount);
    }

    public BigDecimal getReturnDeliveryCharge() {
        return returnDeliveryCharge;
    }

    public String getCourier() {
        return courier;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getReturnedAt() {
        return returnedAt;
    }

    public BigDecimal getCourierCharge() {
        return courierCharge;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public Long getExchangeOfOrderId() {
        return exchangeOfOrderId;
    }

    public BigDecimal getExchangeCredit() {
        return exchangeCredit;
    }

    public boolean isExchangeRefund() {
        return exchangeRefund;
    }

    public DeliveryPaidBy getExchangeDeliveryPaidBy() {
        return exchangeDeliveryPaidBy;
    }

    public ExchangeReason getExchangeReason() {
        return exchangeReason;
    }

    public String getExchangeReturnedSummary() {
        return exchangeReturnedSummary;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public List<OrderStatusChange> getStatusHistory() {
        return Collections.unmodifiableList(statusHistory);
    }
}
