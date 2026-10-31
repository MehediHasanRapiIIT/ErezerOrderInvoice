package com.erezer.admin.boost;

import com.erezer.admin.common.persistence.AuditableEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.CreatedBy;

/** Dollars bought for boosting, in one or more parts, and what each dollar cost on average. */
@Entity
@Table(name = "boost_purchases")
public class BoostPurchase extends AuditableEntity {

    @Embeddable
    public static class Line {

        @Column(name = "usd", nullable = false, precision = 12, scale = 2)
        private BigDecimal usd;

        @Column(name = "bdt", nullable = false, precision = 14, scale = 2)
        private BigDecimal bdt;

        protected Line() {
            // for JPA
        }

        Line(BigDecimal usd, BigDecimal bdt) {
            this.usd = usd;
            this.bdt = bdt;
        }

        public BigDecimal getUsd() {
            return usd;
        }

        public BigDecimal getBdt() {
            return bdt;
        }
    }

    @Column(name = "purchase_date", nullable = false)
    private LocalDate purchaseDate;

    @Column(name = "usd", nullable = false, precision = 14, scale = 2)
    private BigDecimal usd;

    @Column(name = "bdt", nullable = false, precision = 16, scale = 2)
    private BigDecimal bdt;

    @Column(name = "rate", nullable = false, precision = 12, scale = 4)
    private BigDecimal rate;

    @Column(name = "note", length = 200)
    private String note;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @ElementCollection
    @CollectionTable(name = "boost_purchase_lines", joinColumns = @JoinColumn(name = "purchase_id"))
    @OrderColumn(name = "position")
    private List<Line> lines = new ArrayList<>();

    protected BoostPurchase() {
        // for JPA
    }

    BoostPurchase(LocalDate purchaseDate, List<Line> lines, String note) {
        update(purchaseDate, lines, note);
    }

    final void update(LocalDate purchaseDate, List<Line> lines, String note) {
        this.purchaseDate = purchaseDate;
        this.lines.clear();
        this.lines.addAll(lines);
        this.usd = lines.stream().map(Line::getUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        this.bdt = lines.stream().map(Line::getBdt).reduce(BigDecimal.ZERO, BigDecimal::add);
        this.rate = bdt.divide(usd, 4, RoundingMode.HALF_UP);
        this.note = note;
    }

    public LocalDate getPurchaseDate() {
        return purchaseDate;
    }

    public BigDecimal getUsd() {
        return usd;
    }

    public BigDecimal getBdt() {
        return bdt;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public String getNote() {
        return note;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public List<Line> getLines() {
        return lines;
    }
}
