package com.erezer.admin.expense;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, Long> {

    List<ExpenseCategory> findAllByOrderByNameAsc();

    @Query("select count(c) > 0 from ExpenseCategory c where lower(c.name) = lower(:name) and c.id <> :excludeId")
    boolean nameTaken(@Param("name") String name, @Param("excludeId") long excludeId);
}
