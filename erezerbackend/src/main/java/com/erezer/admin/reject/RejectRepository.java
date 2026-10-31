package com.erezer.admin.reject;

import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RejectRepository extends JpaRepository<Reject, Long> {

    /**
     * @param kinds     the kinds to include
     * @param fromStock whether from stock; both true and false to include everything
     * @param like      a lower-case pattern, such as %black%
     */
    @Query("""
            select r from Reject r
            where r.rejectDate between :from and :to and r.kind in :kinds and r.fromStock in :fromStock
              and (lower(r.variantName) like :like or lower(coalesce(r.note, '')) like :like)
            """)
    Page<Reject> search(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("kinds") java.util.Collection<Reject.Kind> kinds,
            @Param("fromStock") java.util.Collection<Boolean> fromStock, @Param("like") String like, Pageable pageable);
}
