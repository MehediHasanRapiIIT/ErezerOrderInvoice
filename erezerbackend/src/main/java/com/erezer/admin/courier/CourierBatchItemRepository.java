package com.erezer.admin.courier;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface CourierBatchItemRepository extends JpaRepository<CourierBatchItem, Long> {

    List<CourierBatchItem> findByBatchIdOrderByPosition(long batchId);

    long countByBatchIdAndStatus(long batchId, CourierBatchItem.Status status);
}
