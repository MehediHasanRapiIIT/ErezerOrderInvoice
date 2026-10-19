package com.erezer.admin.stock;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    Page<StockMovement> findByVariantIdOrderByCreatedAtDescIdDesc(long variantId, Pageable pageable);
}
