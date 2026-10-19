package com.erezer.admin.stock;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SizeRepository extends JpaRepository<Size, Long> {

    /** The managed list (without the "No size" row), in the admin's order. */
    @Query("select s from Size s where s.legacy = false order by s.position, s.id")
    List<Size> managed();

    @Query("select count(s) > 0 from Size s where lower(s.name) = lower(:name) and s.id <> :excludeId")
    boolean nameTaken(@Param("name") String name, @Param("excludeId") long excludeId);

    @Query("select coalesce(max(s.position), 0) from Size s")
    int maxPosition();
}
