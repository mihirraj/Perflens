package com.acme.shop;

import com.acme.shop.domain.AppUser;
import com.acme.shop.domain.AppUserRepository;
import com.acme.shop.domain.Customer;
import com.acme.shop.domain.CustomerRepository;
import com.acme.shop.domain.OrderLine;
import com.acme.shop.domain.OrderLineRepository;
import com.acme.shop.domain.ShopOrder;
import com.acme.shop.domain.ShopOrderRepository;
import com.acme.shop.service.AuthService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Seeds the in-memory database. Row-by-row inserts: PerfLens reports them as a write N+1. */
@Component
public class DataLoader implements CommandLineRunner {
    private final CustomerRepository customers;
    private final ShopOrderRepository orders;
    private final OrderLineRepository lines;
    private final AppUserRepository users;

    public DataLoader(CustomerRepository customers, ShopOrderRepository orders, OrderLineRepository lines,
                      AppUserRepository users) {
        this.customers = customers;
        this.orders = orders;
        this.lines = lines;
        this.users = users;
    }

    @Override
    @Transactional
    public void run(String... args) {
        List<Customer> cs = new ArrayList<>();
        for (int i = 1; i <= 200; i++) cs.add(new Customer("Customer " + i, "user" + i + "@example.com", i % 5 == 0 ? "GOLD" : "STANDARD"));
        customers.saveAll(cs);
        List<ShopOrder> os = new ArrayList<>();
        List<OrderLine> ls = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            ShopOrder o = new ShopOrder(cs.get(i % cs.size()), LocalDate.of(2026, 1, 1).plusDays(i % 300));
            os.add(o);
            for (int j = 0; j < 4; j++) {
                ls.add(new OrderLine(o, "product-" + ((i + j) % 40), (i + j) % 5 + 1, BigDecimal.valueOf(500 + (i * j) % 9000, 2)));
            }
        }
        orders.saveAll(os);
        lines.saveAll(ls);
        users.save(new AppUser("alice", AuthService.hash("wonderland")));
    }
}
