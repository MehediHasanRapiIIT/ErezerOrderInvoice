package com.erezer.admin.order;

import com.erezer.admin.file.StoredFile;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "product_name", nullable = false, length = 160)
    private String productName;

    @Column(name = "product_code", length = 60)
    private String productCode;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "color", length = 60)
    private String color;

    @Column(name = "size", length = 30)
    private String size;

    /** The stock variant picked for this item; its name is kept in {@code color}. Null = not tracked in stock. */
    @Column(name = "variant_id")
    private Long variantId;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<OrderItemImage> images = new ArrayList<>();

    protected OrderItem() {
        // for JPA
    }

    OrderItem(String productName, String productCode, String description, String color, String size,
            BigDecimal unitPrice, int quantity, List<StoredFile> imageFiles) {
        this.productName = productName;
        this.productCode = productCode;
        this.description = description;
        this.color = color;
        this.size = size;
        this.unitPrice = Order.money(unitPrice);
        this.quantity = quantity;
        this.lineTotal = Order.money(this.unitPrice.multiply(BigDecimal.valueOf(quantity)));
        for (int i = 0; i < imageFiles.size(); i++) {
            images.add(new OrderItemImage(this, imageFiles.get(i), i));
        }
    }

    void useVariant(long variantId, String variantName) {
        this.variantId = variantId;
        this.color = variantName;
    }

    public Long getVariantId() {
        return variantId;
    }

    void attachTo(Order order, int position) {
        this.order = order;
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public int getPosition() {
        return position;
    }

    public String getProductName() {
        return productName;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getDescription() {
        return description;
    }

    public String getColor() {
        return color;
    }

    public String getSize() {
        return size;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public List<OrderItemImage> getImages() {
        return Collections.unmodifiableList(images);
    }
}
