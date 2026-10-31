package com.acme.shop.service;

import com.acme.shop.domain.Customer;
import com.acme.shop.domain.CustomerRepository;
import com.acme.shop.domain.OrderLineRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class ReportService {
    private final OrderLineRepository lines;
    private final CustomerRepository customers;

    public ReportService(OrderLineRepository lines, CustomerRepository customers) {
        this.lines = lines;
        this.customers = customers;
    }

    /** BOTTLENECK: slow query (self-join on an unindexed column). */
    public List<Object[]> productPairs(int minQuantity) {
        return lines.productPairs(minQuantity);
    }

    /** BOTTLENECK: LOWER(column) + leading '%' wildcard defeat any index. */
    public List<Customer> searchCustomers(String fragment) {
        return customers.searchByEmail("%" + fragment.toLowerCase(Locale.ROOT) + "%");
    }
}
