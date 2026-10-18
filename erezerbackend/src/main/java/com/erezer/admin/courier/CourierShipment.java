package com.erezer.admin.courier;

import com.erezer.admin.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.annotation.CreatedBy;

/** One courier booking for an order (at most one per order, enforced by a unique index). */
@Entity
@Table(name = "courier_shipments")
public class CourierShipment extends AuditableEntity {

    public enum State {
        /** Sent to Steadfast but the outcome is unknown (timeout); the poller resolves it via the invoice. */
        CONFIRMING,
        BOOKED
    }

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private State state;

    @Column(name = "invoice", nullable = false, length = 100)
    private String invoice;

    @Column(name = "consignment_id", length = 40)
    private String consignmentId;

    @Column(name = "tracking_code", length = 60)
    private String trackingCode;

    @Column(name = "tracking_link", length = 300)
    private String trackingLink;

    @Column(name = "cod_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal codAmount;

    @Column(name = "delivery_type", nullable = false)
    private short deliveryType;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "item_description", length = 255)
    private String itemDescription;

    @Column(name = "courier_status", length = 60)
    private String courierStatus;

    @Column(name = "courier_status_at")
    private Instant courierStatusAt;

    @Column(name = "tracking_message", length = 500)
    private String trackingMessage;

    @Column(name = "delivery_charge", precision = 12, scale = 2)
    private BigDecimal deliveryCharge;

    @Column(name = "needs_attention", nullable = false)
    private boolean needsAttention;

    @Column(name = "request_json")
    private String requestJson;

    @Column(name = "response_json")
    private String responseJson;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    protected CourierShipment() {
        // for JPA
    }

    CourierShipment(long orderId, String provider, String invoice, BigDecimal codAmount, int deliveryType, String note,
            String itemDescription, String requestJson, Long createdBy) {
        this.createdBy = createdBy; // set explicitly: bulk sends run in the background without a signed-in user
        this.orderId = orderId;
        this.provider = provider;
        this.state = State.CONFIRMING;
        this.invoice = invoice;
        this.codAmount = codAmount;
        this.deliveryType = (short) deliveryType;
        this.note = note;
        this.itemDescription = itemDescription;
        this.requestJson = requestJson;
    }

    void markBooked(String consignmentId, String trackingCode, String trackingLink, String status, String responseJson,
            Instant now) {
        this.state = State.BOOKED;
        this.consignmentId = consignmentId;
        this.trackingCode = trackingCode;
        this.trackingLink = trackingLink;
        this.responseJson = responseJson;
        updateStatus(status, null, null, now);
    }

    /** Booked, but Steadfast's reply was lost; the consignment id arrives later via webhook. */
    void markBookedWithoutReply(String status, Instant now) {
        this.state = State.BOOKED;
        updateStatus(status, null, null, now);
    }

    void fillIdentifiers(String consignmentId, String trackingCode) {
        if (this.consignmentId == null && consignmentId != null) {
            this.consignmentId = consignmentId;
        }
        if (this.trackingCode == null && trackingCode != null) {
            this.trackingCode = trackingCode;
        }
    }

    void updateStatus(String status, String trackingMessage, BigDecimal deliveryCharge, Instant now) {
        if (status != null) {
            this.courierStatus = status;
        }
        if (trackingMessage != null) {
            this.trackingMessage = trackingMessage.length() > 500 ? trackingMessage.substring(0, 500) : trackingMessage;
        }
        if (deliveryCharge != null) {
            this.deliveryCharge = deliveryCharge;
        }
        this.courierStatusAt = now;
    }

    void setNeedsAttention(boolean needsAttention) {
        this.needsAttention = needsAttention;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getProvider() {
        return provider;
    }

    public State getState() {
        return state;
    }

    public String getInvoice() {
        return invoice;
    }

    public String getConsignmentId() {
        return consignmentId;
    }

    public String getTrackingCode() {
        return trackingCode;
    }

    public String getTrackingLink() {
        return trackingLink;
    }

    public BigDecimal getCodAmount() {
        return codAmount;
    }

    public int getDeliveryType() {
        return deliveryType;
    }

    public String getNote() {
        return note;
    }

    public String getItemDescription() {
        return itemDescription;
    }

    public String getCourierStatus() {
        return courierStatus;
    }

    public Instant getCourierStatusAt() {
        return courierStatusAt;
    }

    public String getTrackingMessage() {
        return trackingMessage;
    }

    public BigDecimal getDeliveryCharge() {
        return deliveryCharge;
    }

    public boolean isNeedsAttention() {
        return needsAttention;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
