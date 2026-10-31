package com.erezer.admin.boost;

import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BoostCostRepository extends JpaRepository<BoostCost, Long> {

    /** @param like a lower-case pattern, such as %eid sale% */
    @Query("""
            select c from BoostCost c
            where c.costDate between :from and :to and lower(coalesce(c.note, '')) like :like
            """)
    Page<BoostCost> search(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("like") String like, Pageable pageable);
}
