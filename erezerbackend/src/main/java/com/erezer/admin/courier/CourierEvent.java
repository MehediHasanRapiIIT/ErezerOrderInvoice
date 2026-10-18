package com.erezer.admin.courier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A status update received from the courier (webhook) or fetched by us (poll / manual refresh). */
@Entity
@Table(name = "courier_events")
class CourierEvent {

    enum Source { WEBHOOK, POLL, MANUAL, BOOKING }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shipment_id")
    private Long shipmentId;

    @Column(name = "source", nullable = false, length = 20)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Source source;

    @Column(name = "dedupe_key", length = 128)
    private String dedupeKey;

    @Column(name = "courier_status", length = 60)
    private String courierStatus;

    @Column(name = "payload")
    private String payload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected CourierEvent() {
    }

    CourierEvent(Long shipmentId, Source source, String dedupeKey, String courierStatus, String payload, Instant receivedAt) {
        this.shipmentId = shipmentId;
        this.source = source;
        this.dedupeKey = dedupeKey;
        this.courierStatus = courierStatus;
        this.payload = payload;
        this.receivedAt = receivedAt;
    }

    Source getSource() {
        return source;
    }

    String getCourierStatus() {
        return courierStatus;
    }

    Instant getReceivedAt() {
        return receivedAt;
    }
}
