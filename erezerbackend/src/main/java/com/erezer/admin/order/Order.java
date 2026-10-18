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

    /** What the courier actually charged for this parcel (reported by the courier); null when unknown. */
    @Column(name = "courier_charge", precision = 12, scale = 2)
    private BigDecimal courierCharge;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

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
        if (discount.compareTo(subtotal) > 0) {
            throw ApiException.badRequest("DISCOUNT_TOO_LARGE", "Discount cannot exceed the product subtotal");
        }
        total = subtotal.subtract(discount).add(deliveryCharge);
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

    void markDeleted(Instant now) {
        this.deletedAt = now;
    }

    public boolean isEditable() {
        return status != OrderStatus.CANCELLED && status != OrderStatus.RETURNED;
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

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public List<OrderStatusChange> getStatusHistory() {
        return Collections.unmodifiableList(statusHistory);
    }
}
