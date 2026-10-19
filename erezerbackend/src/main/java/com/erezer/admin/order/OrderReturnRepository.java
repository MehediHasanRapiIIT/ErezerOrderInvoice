package com.erezer.admin.order;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderReturnRepository extends JpaRepository<OrderReturn, Long> {

    @EntityGraph(attributePaths = "items")
    List<OrderReturn> findByOrderIdOrderByCreatedAtDescIdDesc(long orderId);
}
