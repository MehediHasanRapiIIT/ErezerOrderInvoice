package com.erezer.admin.expense;

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

@Entity
@Table(name = "expenses")
@SQLRestriction("deleted_at is null")
public class Expense extends AuditableEntity {

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private ExpenseCategory category;

    @Column(name = "description", nullable = false, length = 200)
    private String description;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    /** Bill, voucher or transaction number. */
    @Column(name = "reference", length = 80)
    private String reference;

    @Column(name = "notes", length = 1000)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_file_id")
    private StoredFile receipt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    protected Expense() {
        // for JPA
    }

    Expense(LocalDate expenseDate, ExpenseCategory category, String description, BigDecimal amount,
            PaymentMethod paymentMethod, String reference, String notes, StoredFile receipt) {
        update(expenseDate, category, description, amount, paymentMethod, reference, notes, receipt);
    }

    void update(LocalDate expenseDate, ExpenseCategory category, String description, BigDecimal amount,
            PaymentMethod paymentMethod, String reference, String notes, StoredFile receipt) {
        this.expenseDate = expenseDate;
        this.category = category;
        this.description = description;
        this.amount = amount.setScale(2, RoundingMode.HALF_UP);
        this.paymentMethod = paymentMethod;
        this.reference = reference;
        this.notes = notes;
        this.receipt = receipt;
    }

    void markDeleted(Instant now) {
        this.deletedAt = now;
    }

    public LocalDate getExpenseDate() {
        return expenseDate;
    }

    public ExpenseCategory getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public String getReference() {
        return reference;
    }

    public String getNotes() {
        return notes;
    }

    public StoredFile getReceipt() {
        return receipt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
