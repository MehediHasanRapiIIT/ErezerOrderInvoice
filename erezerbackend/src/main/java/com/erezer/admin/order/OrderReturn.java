package com.erezer.admin.order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Products that came back: a refused parcel (whole order) or a return after delivery (some or all products).
 * No items means the whole order (refused parcels, and returns recorded before itemised returns existed).
 */
@Entity
@Table(name = "order_returns")
public class OrderReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20, updatable = false)
    private ReturnKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private ReturnReason reason;

    @Column(name = "return_charge", nullable = false, precision = 12, scale = 2)
    private BigDecimal returnCharge;

    @Enumerated(EnumType.STRING)
    @Column(name = "paid_by", nullable = false, length = 10)
    private DeliveryPaidBy paidBy;

    @Column(name = "items_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal itemsValue;

    @Column(name = "refund_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "restock", nullable = false)
    private boolean restock;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @OneToMany(mappedBy = "orderReturn", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderReturnItem> items = new ArrayList<>();

    protected OrderReturn() {
        // for JPA
    }

    OrderReturn(long orderId, ReturnKind kind, ReturnReason reason, boolean restock, String note, Long createdBy, Instant now) {
        this.orderId = orderId;
        this.kind = kind;
        this.reason = reason;
        this.restock = restock;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
        this.returnCharge = BigDecimal.ZERO;
        this.itemsValue = BigDecimal.ZERO;
        this.refundAmount = BigDecimal.ZERO;
        this.paidBy = DeliveryPaidBy.EREZER;
    }

    void addItem(OrderItem item, int quantity, BigDecimal unitCredit) {
        items.add(new OrderReturnItem(this, item, quantity, unitCredit));
    }

    /**
     * Sets the charge and who pays it, and works out the refund: after delivery, the items' value less the return
     * charge when the customer pays it; for a refused parcel, any advance less what the customer covers (the
     * courier's delivery + return charge).
     */
    void settle(BigDecimal charge, DeliveryPaidBy payer, BigDecimal itemsValue, BigDecimal advance,
            BigDecimal refusedDeliveryCost, Instant now) {
        this.returnCharge = Order.money(charge);
        this.paidBy = payer;
        this.itemsValue = Order.money(itemsValue);
        BigDecimal owed = kind == ReturnKind.REFUSED ? this.returnCharge.add(Order.money(refusedDeliveryCost)) : this.returnCharge;
        BigDecimal customerShare = payer == DeliveryPaidBy.CUSTOMER ? owed : BigDecimal.ZERO;
        BigDecimal base = kind == ReturnKind.REFUSED ? Order.money(advance) : this.itemsValue;
        this.refundAmount = base.subtract(customerShare).max(BigDecimal.ZERO);
        this.updatedAt = now;
    }

    void edit(ReturnReason reason, String note, Instant now) {
        this.reason = reason;
        this.note = note;
        this.updatedAt = now;
    }

    void cancel(Instant now) {
        this.cancelledAt = now;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return cancelledAt == null;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public ReturnKind getKind() {
        return kind;
    }

    public ReturnReason getReason() {
        return reason;
    }

    public BigDecimal getReturnCharge() {
        return returnCharge;
    }

    public DeliveryPaidBy getPaidBy() {
        return paidBy;
    }

    public BigDecimal getItemsValue() {
        return itemsValue;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public boolean isRestock() {
        return restock;
    }

    public String getNote() {
        return note;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public List<OrderReturnItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
