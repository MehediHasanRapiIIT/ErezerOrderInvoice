package com.erezer.admin.employee;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface EmployeeRepository extends JpaRepository<Employee, Long> {

    boolean existsByEmployeeCodeIgnoreCase(String employeeCode);

    boolean existsByEmployeeCodeIgnoreCaseAndIdNot(String employeeCode, long id);

    boolean existsByNidNumber(String nidNumber);

    boolean existsByNidNumberAndIdNot(String nidNumber, long id);

    @Override
    @EntityGraph(attributePaths = "photo")
    Page<Employee> findAll(Pageable pageable);

    /** @param like a lower-case pattern, such as %rahim% */
    @EntityGraph(attributePaths = "photo")
    @Query("""
            select e from Employee e
            where lower(e.fullName) like :like or lower(e.employeeCode) like :like
               or e.phone like :like or e.nidNumber like :like
            """)
    Page<Employee> search(@Param("like") String like, Pageable pageable);
}
