package com.erezer.admin.courier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** One order in a bulk send, with the (possibly edited) values to book and the outcome. */
@Entity
@Table(name = "courier_batch_items")
class CourierBatchItem {

    enum Status { PENDING, SUCCESS, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "cod_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal codAmount;

    @Column(name = "delivery_type", nullable = false)
    private short deliveryType;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "item_description", length = 255)
    private String itemDescription;

    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "tracking_code", length = 60)
    private String trackingCode;

    @Column(name = "position", nullable = false)
    private int position;

    protected CourierBatchItem() {
    }

    CourierBatchItem(long batchId, long orderId, BigDecimal codAmount, int deliveryType, String note,
            String itemDescription, int position) {
        this.batchId = batchId;
        this.orderId = orderId;
        this.codAmount = codAmount;
        this.deliveryType = (short) deliveryType;
        this.note = note;
        this.itemDescription = itemDescription;
        this.position = position;
    }

    void succeed(String trackingCode, String info) {
        this.status = Status.SUCCESS;
        this.trackingCode = trackingCode;
        this.message = info;
    }

    void fail(String message) {
        this.status = Status.FAILED;
        this.message = message == null ? "Failed" : message.length() > 500 ? message.substring(0, 500) : message;
    }

    Long getId() { return id; }
    Long getBatchId() { return batchId; }
    Long getOrderId() { return orderId; }
    Status getStatus() { return status; }
    BigDecimal getCodAmount() { return codAmount; }
    int getDeliveryType() { return deliveryType; }
    String getNote() { return note; }
    String getItemDescription() { return itemDescription; }
    String getMessage() { return message; }
    String getTrackingCode() { return trackingCode; }
}
