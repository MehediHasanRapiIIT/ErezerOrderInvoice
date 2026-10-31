package com.erezer.admin.boost;

import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BoostPurchaseRepository extends JpaRepository<BoostPurchase, Long> {

    /** @param like a lower-case pattern, such as %agent% */
    @Query("""
            select p from BoostPurchase p
            where p.purchaseDate between :from and :to and lower(coalesce(p.note, '')) like :like
            """)
    Page<BoostPurchase> search(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("like") String like, Pageable pageable);
}
