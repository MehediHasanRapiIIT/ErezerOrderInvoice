package com.erezer.admin.stock;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockVariantRepository extends JpaRepository<StockVariant, Long>, JpaSpecificationExecutor<StockVariant> {

    /** Locks the rows so two orders cannot both take the last pieces. Ordered by id to avoid deadlocks. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from StockVariant v where v.id in :ids order by v.id")
    List<StockVariant> lockAll(@Param("ids") Collection<Long> ids);

    @Query("select count(v) > 0 from StockVariant v where lower(v.name) = lower(:name) and v.id <> :excludeId")
    boolean nameTaken(@Param("name") String name, @Param("excludeId") long excludeId);

    @Query("select lower(v.name) from StockVariant v where lower(v.name) in :names")
    List<String> takenNames(@Param("names") Collection<String> lowerCaseNames);
}
