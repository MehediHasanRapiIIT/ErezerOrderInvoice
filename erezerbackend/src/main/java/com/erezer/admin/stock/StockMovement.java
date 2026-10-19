package com.erezer.admin.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One change of a variant + size's quantity: what, why, by whom and that size's quantity after it. */
@Entity
@Table(name = "stock_movements")
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private Long variantId;

    @Column(name = "stock_item_id", nullable = false, updatable = false)
    private Long stockItemId;

    @Column(name = "change", nullable = false, updatable = false)
    private int change;

    @Column(name = "quantity_after", nullable = false, updatable = false)
    private int quantityAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30, updatable = false)
    private StockReason reason;

    @Column(name = "order_id", updatable = false)
    private Long orderId;

    @Column(name = "note", length = 200, updatable = false)
    private String note;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StockMovement() {
        // for JPA
    }

    StockMovement(long variantId, long stockItemId, int change, int quantityAfter, StockReason reason, Long orderId, String note,
            Long createdBy, Instant createdAt) {
        this.variantId = variantId;
        this.stockItemId = stockItemId;
        this.change = change;
        this.quantityAfter = quantityAfter;
        this.reason = reason;
        this.orderId = orderId;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getVariantId() {
        return variantId;
    }

    public Long getStockItemId() {
        return stockItemId;
    }

    public int getChange() {
        return change;
    }

    public int getQuantityAfter() {
        return quantityAfter;
    }

    public StockReason getReason() {
        return reason;
    }

    public Long getOrderId() {
        return orderId;
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
}
