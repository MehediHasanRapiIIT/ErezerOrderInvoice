package com.erezer.admin.courier;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CourierShipmentRepository extends JpaRepository<CourierShipment, Long> {

    Optional<CourierShipment> findByOrderId(long orderId);

    List<CourierShipment> findByOrderIdIn(List<Long> orderIds);

    Optional<CourierShipment> findFirstByConsignmentId(String consignmentId);

    Optional<CourierShipment> findFirstByInvoiceOrderByIdDesc(String invoice);

    /** Bookings still in flight that have not been updated recently (backup for missed webhooks). */
    @Query("""
            select s from CourierShipment s
            where (s.state = com.erezer.admin.courier.CourierShipment.State.CONFIRMING and s.updatedAt < :confirmBefore)
               or (s.state = com.erezer.admin.courier.CourierShipment.State.BOOKED
                   and (s.courierStatus is null or s.courierStatus not in :finalStatuses)
                   and (s.courierStatusAt is null or s.courierStatusAt < :staleBefore))
            order by s.courierStatusAt asc nulls first
            """)
    List<CourierShipment> findDueForSync(@Param("confirmBefore") Instant confirmBefore,
            @Param("staleBefore") Instant staleBefore, @Param("finalStatuses") List<String> finalStatuses, Limit limit);
}
