package com.erezer.admin.dailyexpense;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface DailyExpenseRepository extends JpaRepository<DailyExpense, Long> {

    @EntityGraph(attributePaths = {"category", "receipt"})
    List<DailyExpense> findByIdIn(Collection<Long> ids);

    long countByCategoryId(long categoryId);
}
