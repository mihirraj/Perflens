package com.acme.shop.service;

import com.acme.shop.domain.OrderLine;
import com.acme.shop.domain.ShopOrder;
import com.acme.shop.domain.ShopOrderRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class OrderService {
    private final ShopOrderRepository orders;

    public OrderService(ShopOrderRepository orders) {
        this.orders = orders;
    }

    public record OrderSummary(Long id, String customer, int lines, BigDecimal total) {}

    /** BOTTLENECK: N+1. Each order lazily loads its customer and its lines one query at a time. */
    @Transactional(readOnly = true)
    public List<OrderSummary> summarize(int limit) {
        List<OrderSummary> out = new ArrayList<>();
        for (ShopOrder o : orders.findAll(PageRequest.of(0, limit)).getContent()) {
            BigDecimal total = BigDecimal.ZERO;
            for (OrderLine l : o.getLines()) {
                total = total.add(l.getUnitPrice().multiply(BigDecimal.valueOf(l.getQuantity())));
            }
            out.add(new OrderSummary(o.getId(), o.getCustomer().getName(), o.getLines().size(), total));
        }
        return out;
    }
}
