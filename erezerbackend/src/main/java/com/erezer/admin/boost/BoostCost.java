package com.erezer.admin.boost;

import com.erezer.admin.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.springframework.data.annotation.CreatedBy;

/** Dollars spent on boosting on one day, and what that came to in taka. */
@Entity
@Table(name = "boost_costs")
public class BoostCost extends AuditableEntity {

    @Column(name = "cost_date", nullable = false)
    private LocalDate costDate;

    @Column(name = "usd", nullable = false, precision = 12, scale = 2)
    private BigDecimal usd;

    @Column(name = "rate", nullable = false, precision = 12, scale = 4)
    private BigDecimal rate;

    @Column(name = "bdt", nullable = false, precision = 16, scale = 2)
    private BigDecimal bdt;

    @Column(name = "note", length = 200)
    private String note;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    protected BoostCost() {
        // for JPA
    }

    BoostCost(LocalDate costDate, BigDecimal usd, BigDecimal rate, String note) {
        update(costDate, usd, rate, note);
    }

    final void update(LocalDate costDate, BigDecimal usd, BigDecimal rate, String note) {
        this.costDate = costDate;
        this.usd = usd;
        this.rate = rate;
        this.bdt = usd.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        this.note = note;
    }

    public LocalDate getCostDate() {
        return costDate;
    }

    public BigDecimal getUsd() {
        return usd;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public BigDecimal getBdt() {
        return bdt;
    }

    public String getNote() {
        return note;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
