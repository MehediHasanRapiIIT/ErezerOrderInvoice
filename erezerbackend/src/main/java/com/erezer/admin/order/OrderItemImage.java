package com.erezer.admin.order;

import com.erezer.admin.file.StoredFile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_item_images")
public class OrderItemImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem item;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private StoredFile file;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "description", length = 500)
    private String description;

    protected OrderItemImage() {
        // for JPA
    }

    OrderItemImage(OrderItem item, StoredFile file, int position, String description) {
        this.item = item;
        this.file = file;
        this.position = position;
        this.description = description;
    }

    public StoredFile getFile() {
        return file;
    }

    public String getDescription() {
        return description;
    }

    public int getPosition() {
        return position;
    }
}
