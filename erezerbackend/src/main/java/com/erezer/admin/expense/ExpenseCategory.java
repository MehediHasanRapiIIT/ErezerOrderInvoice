package com.erezer.admin.expense;

import com.erezer.admin.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "expense_categories")
public class ExpenseCategory extends AuditableEntity {

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected ExpenseCategory() {
        // for JPA
    }

    ExpenseCategory(String name) {
        this.name = name;
    }

    void rename(String name) {
        this.name = name;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }
}
