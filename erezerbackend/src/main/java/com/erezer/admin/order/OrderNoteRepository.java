package com.erezer.admin.order;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrderNoteRepository extends JpaRepository<OrderNote, Long> {

    List<OrderNote> findByOrderIdOrderByCreatedAtAscIdAsc(long orderId);
}
