package com.erezer.admin.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** One returned order line: how many came back and the credit per piece. Product details are a copy. */
@Entity
@Table(name = "order_return_items")
public class OrderReturnItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", nullable = false, updatable = false)
    private OrderReturn orderReturn;

    @Column(name = "order_item_id", nullable = false, updatable = false)
    private Long orderItemId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "unit_credit", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal unitCredit;

    @Column(name = "product_name", nullable = false, length = 160, updatable = false)
    private String productName;

    @Column(name = "product_code", length = 60, updatable = false)
    private String productCode;

    @Column(name = "color", length = 80, updatable = false)
    private String color;

    @Column(name = "size", length = 30, updatable = false)
    private String size;

    protected OrderReturnItem() {
        // for JPA
    }

    OrderReturnItem(OrderReturn orderReturn, OrderItem item, int quantity, BigDecimal unitCredit) {
        this.orderReturn = orderReturn;
        this.orderItemId = item.getId();
        this.quantity = quantity;
        this.unitCredit = unitCredit;
        this.productName = item.getProductName();
        this.productCode = item.getProductCode();
        this.color = item.getColor();
        this.size = item.getSize();
    }

    public Long getOrderItemId() {
        return orderItemId;
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
}
