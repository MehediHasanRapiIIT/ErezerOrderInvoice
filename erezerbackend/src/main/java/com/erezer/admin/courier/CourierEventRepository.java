package com.erezer.admin.courier;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface CourierEventRepository extends JpaRepository<CourierEvent, Long> {

    boolean existsByDedupeKey(String dedupeKey);

    List<CourierEvent> findTop20ByShipmentIdOrderByReceivedAtDesc(long shipmentId);
}
