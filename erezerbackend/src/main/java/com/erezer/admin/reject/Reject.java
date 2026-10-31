package com.erezer.admin.reject;

import com.erezer.admin.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.springframework.data.annotation.CreatedBy;

/** Rejected pieces, from a stock variant or a typed "Other" name. */
@Entity
@Table(name = "rejects")
public class Reject extends AuditableEntity {

    public enum Kind {
        SOLID,
        PRINTED
    }

    /** What the form sets. {@code stockItemId} is null for "Other". */
    record Details(LocalDate date, Long variantId, Long stockItemId, String variantName, String size, Kind kind, int quantity,
            String note) {
    }

    @Column(name = "reject_date", nullable = false)
    private LocalDate rejectDate;

    @Column(name = "from_stock", nullable = false)
    private boolean fromStock;

    @Column(name = "variant_id")
    private Long variantId;

    @Column(name = "stock_item_id")
    private Long stockItemId;

    @Column(name = "variant_name", nullable = false, length = 120)
    private String variantName;

    @Column(name = "size", length = 30)
    private String size;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private Kind kind;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "note", length = 300)
    private String note;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    protected Reject() {
        // for JPA
    }

    Reject(Details details) {
        apply(details);
    }

    final void apply(Details d) {
        this.rejectDate = d.date();
        this.fromStock = d.stockItemId() != null;
        this.variantId = d.variantId();
        this.stockItemId = d.stockItemId();
        this.variantName = d.variantName();
        this.size = d.size();
        this.kind = d.kind();
        this.quantity = d.quantity();
        this.note = d.note();
    }

    public LocalDate getRejectDate() {
        return rejectDate;
    }

    public boolean isFromStock() {
        return fromStock;
    }

    public Long getVariantId() {
        return variantId;
    }

    public Long getStockItemId() {
        return stockItemId;
    }

    public String getVariantName() {
        return variantName;
    }

    public String getSize() {
        return size;
    }

    public Kind getKind() {
        return kind;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getNote() {
        return note;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
