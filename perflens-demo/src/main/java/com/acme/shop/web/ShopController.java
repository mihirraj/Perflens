package com.acme.shop.web;

import com.acme.shop.domain.Customer;
import com.acme.shop.service.AuthService;
import com.acme.shop.service.DashboardService;
import com.acme.shop.service.OrderService;
import com.acme.shop.service.ParallelDashboardService;
import com.acme.shop.service.PricingService;
import com.acme.shop.service.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class ShopController {
    private final OrderService orders;
    private final ReportService reports;
    private final PricingService pricing;
    private final DashboardService dashboard;
    private final AuthService auth;
    private final ParallelDashboardService parallelDashboard;

    public ShopController(OrderService orders, ReportService reports, PricingService pricing,
                          DashboardService dashboard, AuthService auth, ParallelDashboardService parallelDashboard) {
        this.orders = orders;
        this.reports = reports;
        this.pricing = pricing;
        this.dashboard = dashboard;
        this.auth = auth;
        this.parallelDashboard = parallelDashboard;
    }

    @GetMapping("/orders/summary")
    public List<OrderService.OrderSummary> summary(@RequestParam(defaultValue = "40") int limit) {
        return orders.summarize(limit);
    }

    @GetMapping("/reports/product-pairs")
    public int productPairs(@RequestParam(defaultValue = "1") int minQuantity) {
        return reports.productPairs(minQuantity).size();
    }

    @GetMapping("/customers/search")
    public List<Customer> search(@RequestParam String q) {
        return reports.searchCustomers(q);
    }

    @GetMapping("/pricing/quote")
    public PricingService.Quote quote(@RequestParam Long customerId, @RequestParam(defaultValue = "400") int items,
                                      @RequestParam(required = false) String coupon) {
        return pricing.quote(customerId, items, coupon);
    }

    @GetMapping("/dashboard/{customerId}")
    public Map<String, Object> dashboard(@PathVariable Long customerId) {
        return dashboard.load(customerId);
    }

    @GetMapping("/dashboard-parallel/{customerId}")
    public Map<String, Object> dashboardParallel(@PathVariable Long customerId) {
        return parallelDashboard.load(customerId);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String username, @RequestParam String password) {
        return Map.of("ok", auth.login(username, password));
    }
}
