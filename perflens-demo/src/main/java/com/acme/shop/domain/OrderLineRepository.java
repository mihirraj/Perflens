package com.acme.shop.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {
    /** Deliberately heavy: self-join on an unindexed column. */
    @Query(value = "select a.product, count(*) from order_line a join order_line b on a.quantity = b.quantity "
            + "and a.id < b.id where a.quantity >= ?1 group by a.product order by count(*) desc", nativeQuery = true)
    List<Object[]> productPairs(int minQuantity);
}
