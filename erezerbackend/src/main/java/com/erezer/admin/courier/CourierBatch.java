package com.erezer.admin.courier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A bulk send. Items are processed in the background; counters are updated as each order finishes. */
@Entity
@Table(name = "courier_batches")
class CourierBatch {

    enum Status { RUNNING, COMPLETED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.RUNNING;

    @Column(name = "total", nullable = false)
    private int total;

    @Column(name = "succeeded", nullable = false)
    private int succeeded;

    @Column(name = "failed", nullable = false)
    private int failed;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected CourierBatch() {
    }

    CourierBatch(String provider, int total, Long createdBy, Instant createdAt) {
        this.provider = provider;
        this.total = total;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    void complete(int succeeded, int failed, Instant now) {
        this.succeeded = succeeded;
        this.failed = failed;
        this.status = Status.COMPLETED;
        this.finishedAt = now;
    }

    Long getId() { return id; }
    Status getStatus() { return status; }
    int getTotal() { return total; }
    int getSucceeded() { return succeeded; }
    int getFailed() { return failed; }
    Long getCreatedBy() { return createdBy; }
    Instant getCreatedAt() { return createdAt; }
    Instant getFinishedAt() { return finishedAt; }
}
