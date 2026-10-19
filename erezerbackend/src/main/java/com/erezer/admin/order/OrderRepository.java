package com.erezer.admin.order;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    /** Loads an order with its items for the detail page; images and history are batch-fetched. */
    @EntityGraph(attributePaths = "items")
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findDetailedById(@Param("id") long id);

    /** Locks the order row so two people cannot send the same order to the courier at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findForCourierById(@Param("id") long id);

    /** Checks soft-deleted orders too, because order numbers are never reused. */
    @Query(value = "select exists(select 1 from orders where order_number = :number and id <> :excludeId)",
            nativeQuery = true)
    boolean orderNumberTaken(@Param("number") String orderNumber, @Param("excludeId") long excludeId);

    /** Delivered orders whose number matches exactly, or whose customer phone matches, for the exchange page. */
    @Query("""
            select o from Order o
            where o.status = com.erezer.admin.order.OrderStatus.DELIVERED
              and (upper(o.orderNumber) = :number or o.customerPhoneKey = :phoneKey)
            order by o.deliveredAt desc
            """)
    List<Order> findExchangeCandidates(@Param("number") String number, @Param("phoneKey") String phoneKey,
            org.springframework.data.domain.Limit limit);

    List<Order> findByExchangeOfOrderIdOrderByCreatedAtDesc(Long exchangeOfOrderId);

    /** The original's number even if it was deleted later. */
    @Query(value = "select order_number from orders where id = :id", nativeQuery = true)
    String findOriginalNumber(@Param("id") long id);

    /** Any exchange ever made for the order, deleted ones included (their item rows still reference it). */
    @Query(value = "select exists(select 1 from orders where exchange_of_order_id = :id)", nativeQuery = true)
    boolean hasExchanges(@Param("id") long id);

    @Query("select o.status as status, count(o) as count from Order o group by o.status")
    List<StatusCount> countByStatus();

    interface StatusCount {
        OrderStatus getStatus();

        long getCount();
    }
}
