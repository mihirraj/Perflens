package com.acme.shop.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    /** Deliberately slow: function on a column + leading-wildcard LIKE (no index can help). */
    @Query(value = "select * from customer c where lower(c.email) like ?1", nativeQuery = true)
    List<Customer> searchByEmail(String pattern);
}
