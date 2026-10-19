package com.erezer.admin.stock;

import com.erezer.admin.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A size in the admin's list (S, M, L, 32, Free size…). The single {@code legacy} row, "No size", holds stock that was
 * counted before sizes existed; it is not part of the managed list.
 */
@Entity
@Table(name = "sizes")
public class Size extends AuditableEntity {

    @Column(name = "name", nullable = false, length = 20)
    private String name;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "legacy", nullable = false, updatable = false)
    private boolean legacy;

    protected Size() {
        // for JPA
    }

    Size(String name, int position) {
        this.name = name;
        this.position = position;
    }

    void rename(String name) {
        this.name = name;
    }

    void moveTo(int position) {
        this.position = position;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    public String getName() {
        return name;
    }

    public int getPosition() {
        return position;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isLegacy() {
        return legacy;
    }
}
