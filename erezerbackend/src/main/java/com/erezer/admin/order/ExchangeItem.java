package com.erezer.admin.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * One original line item (and how many of it) that an exchange takes back. The product details are a copy, so the
 * exchange stays readable on its own.
 */
@Entity
@Table(name = "order_exchange_items")
public class ExchangeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exchange_order_id", nullable = false, updatable = false)
    private Long exchangeOrderId;

    @Column(name = "original_item_id", nullable = false, updatable = false)
    private Long originalItemId;

    /** Position of the replacement line in the exchange order. */
    @Column(name = "replacement_position", nullable = false, updatable = false)
    private int replacementPosition;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    /** Credit per returned unit: the original price less its share of the original order's discount. */
    @Column(name = "unit_credit", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal unitCredit;

    @Column(name = "product_name", nullable = false, length = 160, updatable = false)
    private String productName;

    @Column(name = "product_code", length = 60, updatable = false)
    private String productCode;

    @Column(name = "color", length = 60, updatable = false)
    private String color;

    @Column(name = "size", length = 30, updatable = false)
    private String size;

    protected ExchangeItem() {
        // for JPA
    }

    ExchangeItem(long exchangeOrderId, OrderItem original, int replacementPosition, int quantity, BigDecimal unitCredit) {
        this.exchangeOrderId = exchangeOrderId;
        this.originalItemId = original.getId();
        this.replacementPosition = replacementPosition;
        this.quantity = quantity;
        this.unitCredit = unitCredit;
        this.productName = original.getProductName();
        this.productCode = original.getProductCode();
        this.color = original.getColor();
        this.size = original.getSize();
    }

    public Long getId() {
        return id;
    }

    public Long getExchangeOrderId() {
        return exchangeOrderId;
    }

    public Long getOriginalItemId() {
        return originalItemId;
    }

    public int getReplacementPosition() {
        return replacementPosition;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitCredit() {
        return unitCredit;
    }

    public BigDecimal getCredit() {
        return unitCredit.multiply(BigDecimal.valueOf(quantity));
    }

    public String getProductName() {
        return productName;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getColor() {
        return color;
    }

    public String getSize() {
        return size;
    }

    /** "Regular Fit T-Shirt (M) ×1". */
    public String describe() {
        String variant = color != null && size != null ? color + " / " + size : color != null ? color : size;
        return productName + (variant == null ? "" : " (" + variant + ")") + " ×" + quantity;
    }
}
