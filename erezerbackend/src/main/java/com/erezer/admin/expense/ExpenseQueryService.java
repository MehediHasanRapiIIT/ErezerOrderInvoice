package com.erezer.admin.expense;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.expense.dto.ExpenseDtos.CategoryRef;
import com.erezer.admin.expense.dto.ExpenseDtos.CategoryTotal;
import com.erezer.admin.expense.dto.ExpenseDtos.ExpensePage;
import com.erezer.admin.expense.dto.ExpenseDtos.ExpenseResponse;
import com.erezer.admin.expense.dto.ExpenseDtos.Receipt;
import com.erezer.admin.expense.dto.ExpenseDtos.UserRef;
import com.erezer.admin.file.FileService;
import com.erezer.admin.file.StoredFile;
import com.erezer.admin.user.UserDirectory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ExpenseQueryService {

    private final ExpenseRepository expenses;
    private final UserDirectory userDirectory;
    private final FileService files;
    private final EntityManager entityManager;

    ExpenseQueryService(ExpenseRepository expenses, UserDirectory userDirectory, FileService files,
            EntityManager entityManager) {
        this.expenses = expenses;
        this.userDirectory = userDirectory;
        this.files = files;
        this.entityManager = entityManager;
    }

    public ExpensePage search(ExpenseFilter filter, Pageable pageable) {
        Specification<Expense> spec = filter.toSpecification();
        Page<Expense> page = expenses.findAll(spec, pageable);
        List<CategoryTotal> byCategory = totalsByCategory(spec);
        BigDecimal total = byCategory.stream().map(CategoryTotal::total).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<Long, String> names = userDirectory.namesById(page.getContent().stream().map(Expense::getCreatedBy).toList());
        return new ExpensePage(page.getContent().stream().map(e -> toResponse(e, names)).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(), total, byCategory);
    }

    public ExpenseResponse get(long id) {
        Expense expense = expenses.findById(id).orElseThrow(() -> ApiException.notFound("Expense", id));
        return toResponse(expense, userDirectory.namesById(Collections.singletonList(expense.getCreatedBy())));
    }

    /** Sum and count per category over every expense matching the filter, largest first. */
    private List<CategoryTotal> totalsByCategory(Specification<Expense> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Expense> root = query.from(Expense.class);
        Join<Expense, ExpenseCategory> category = root.join("category");
        Expression<BigDecimal> sum = cb.sum(root.get("amount"));
        Predicate where = spec.toPredicate(root, query, cb);
        query.multiselect(category.get("id"), category.get("name"), sum, cb.count(root))
                .groupBy(category.get("id"), category.get("name"))
                .orderBy(cb.desc(sum));
        if (where != null) {
            query.where(where);
        }
        return entityManager.createQuery(query).getResultList().stream()
                .map(t -> new CategoryTotal(t.get(0, Long.class), t.get(1, String.class), t.get(2, BigDecimal.class),
                        t.get(3, Long.class)))
                .toList();
    }

    private ExpenseResponse toResponse(Expense e, Map<Long, String> names) {
        StoredFile file = e.getReceipt();
        Receipt receipt = file == null ? null
                : new Receipt(file.getId(), files.url(file), file.getContentType(), file.getOriginalName());
        UserRef createdBy = e.getCreatedBy() == null ? null
                : new UserRef(e.getCreatedBy(), UserDirectory.nameOf(names, e.getCreatedBy()));
        return new ExpenseResponse(e.getId(), e.getExpenseDate(),
                new CategoryRef(e.getCategory().getId(), e.getCategory().getName()), e.getDescription(), e.getAmount(),
                e.getPaymentMethod(), e.getReference(), e.getNotes(), receipt, createdBy, e.getCreatedAt(),
                e.getUpdatedAt(), e.getVersion());
    }
}
