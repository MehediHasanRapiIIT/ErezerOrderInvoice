package com.erezer.admin.file;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {

    /**
     * Files older than the cutoff that nothing references. Extend the NOT EXISTS list whenever
     * another table starts referencing stored_files.
     */
    @Query("""
            select f from StoredFile f
            where f.createdAt < :cutoff
              and not exists (select 1 from OrderItemImage i where i.file = f)
              and not exists (select 1 from Expense e where e.receipt = f)
              and not exists (select 1 from Asset a where a.photo = f or a.receipt = f)
              and not exists (select 1 from AttendanceRecord r where r.checkInPhoto = f)
              and not exists (select 1 from DailyExpense d where d.receipt = f)
              and not exists (select 1 from Employee e where e.photo = f or e.nidImage = f or e.idCardImage = f)
            """)
    List<StoredFile> findUnreferencedBefore(@Param("cutoff") Instant cutoff, Limit limit);
}
