package com.erezer.admin.stock;

import com.erezer.admin.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.SQLRestriction;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

/**
 * A stock line the admin names freely ("Black", "Navy Blue"…). Its pieces are counted per size in {@code stock_items};
 * {@code quantity} is the total across sizes, kept up to date by {@link StockService} with SQL.
 */
@Entity
@Table(name = "stock_variants")
@SQLRestriction("deleted_at is null")
public class StockVariant extends AuditableEntity {

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected StockVariant() {
        // for JPA
    }

    StockVariant(String name, int quantity) {
        this.name = name;
        this.quantity = quantity;
    }

    void rename(String name) {
        this.name = name;
    }

    void markDeleted(Instant now) {
        this.deletedAt = now;
    }

    public String getName() {
        return name;
    }

    public int getQuantity() {
        return quantity;
    }
}
