package com.erezer.admin.dailyexpense;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface DailyExpenseCategoryRepository extends JpaRepository<DailyExpenseCategory, Long> {

    List<DailyExpenseCategory> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, long id);
}
