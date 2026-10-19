package com.erezer.admin.order;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeItemRepository extends JpaRepository<ExchangeItem, Long> {

    List<ExchangeItem> findByExchangeOrderIdOrderByReplacementPosition(long exchangeOrderId);
}
