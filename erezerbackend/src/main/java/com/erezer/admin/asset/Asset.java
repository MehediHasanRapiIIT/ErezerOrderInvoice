package com.erezer.admin.asset;

import com.erezer.admin.common.persistence.AuditableEntity;
import com.erezer.admin.file.StoredFile;
import com.erezer.admin.order.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.SQLRestriction;
import org.springframework.data.annotation.CreatedBy;

/**
 * Something the shop owns. One row covers identical items (6 chairs); total cost = quantity × unit cost.
 * Never part of expenses or profit.
 */
@Entity
@Table(name = "assets")
@SQLRestriction("deleted_at is null")
public class Asset extends AuditableEntity {

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private AssetCategory category;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "total_cost", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalCost;

    @Column(name = "purchase_date", nullable = false)
    private LocalDate purchaseDate;

    @Column(name = "supplier", length = 120)
    private String supplier;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "reference", length = 80)
    private String reference;

    @Column(name = "location", length = 80)
    private String location;

    @Column(name = "serial_number", length = 120)
    private String serialNumber;

    @Column(name = "warranty_until")
    private LocalDate warrantyUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AssetStatus status;

    @Column(name = "disposed_on")
    private LocalDate disposedOn;

    @Column(name = "sale_price", precision = 12, scale = 2)
    private BigDecimal salePrice;

    @Column(name = "notes", length = 1000)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "photo_file_id")
    private StoredFile photo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_file_id")
    private StoredFile receipt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    protected Asset() {
        // for JPA
    }

    Asset(Details details, AssetStatus status) {
        update(details);
        this.status = status;
    }

    /** Everything the add / edit form sets (status changes go through {@link #changeStatus}). */
    record Details(String name, AssetCategory category, int quantity, BigDecimal unitCost, LocalDate purchaseDate,
            String supplier, PaymentMethod paymentMethod, String reference, String location, String serialNumber,
            LocalDate warrantyUntil, String notes, StoredFile photo, StoredFile receipt) {
    }

    void update(Details d) {
        this.name = d.name();
        this.category = d.category();
        this.quantity = d.quantity();
        this.unitCost = d.unitCost().setScale(2, RoundingMode.HALF_UP);
        this.totalCost = this.unitCost.multiply(BigDecimal.valueOf(d.quantity())).setScale(2, RoundingMode.HALF_UP);
        this.purchaseDate = d.purchaseDate();
        this.supplier = d.supplier();
        this.paymentMethod = d.paymentMethod();
        this.reference = d.reference();
        this.location = d.location();
        this.serialNumber = d.serialNumber();
        this.warrantyUntil = d.warrantyUntil();
        this.notes = d.notes();
        this.photo = d.photo();
        this.receipt = d.receipt();
    }

    /** Sold / disposed keep the date (and sale price); moving back to an owned status clears them. */
    void changeStatus(AssetStatus status, LocalDate on, BigDecimal salePrice) {
        this.status = status;
        this.disposedOn = status.owned() ? null : on;
        this.salePrice = status == AssetStatus.SOLD && salePrice != null ? salePrice.setScale(2, RoundingMode.HALF_UP) : null;
    }

    void markDeleted(Instant now) {
        this.deletedAt = now;
    }

    public String getName() {
        return name;
    }

    public AssetCategory getCategory() {
        return category;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public BigDecimal getTotalCost() {
        return totalCost;
    }

    public LocalDate getPurchaseDate() {
        return purchaseDate;
    }

    public String getSupplier() {
        return supplier;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public String getReference() {
        return reference;
    }

    public String getLocation() {
        return location;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public LocalDate getWarrantyUntil() {
        return warrantyUntil;
    }

    public AssetStatus getStatus() {
        return status;
    }

    public LocalDate getDisposedOn() {
        return disposedOn;
    }

    public BigDecimal getSalePrice() {
        return salePrice;
    }

    public String getNotes() {
        return notes;
    }

    public StoredFile getPhoto() {
        return photo;
    }

    public StoredFile getReceipt() {
        return receipt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
