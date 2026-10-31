package com.acme.shop.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class PricingService {

    public record LineItem(String sku, int qty, BigDecimal price, String note) {}

    public record Quote(Long customerId, int items, BigDecimal total, int auditLength) {}

    /**
     * BOTTLENECK: CPU hot method. For every line it creates a new ObjectMapper, compiles a regex
     * via String.replaceAll, and formats with String.format.
     */
    public Quote quote(Long customerId, int items, String couponCode) {
        BigDecimal total = BigDecimal.ZERO;
        StringBuilder audit = new StringBuilder();
        for (int i = 0; i < items; i++) {
            LineItem li = new LineItem("SKU-" + i, i % 7 + 1, BigDecimal.valueOf(999 + i, 2), "line note " + i);
            String json;
            try {
                json = new ObjectMapper().writeValueAsString(li);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            String cleaned = json.replaceAll("[^A-Za-z0-9:,{}\"]", "");
            audit.append(String.format("%s:%d;", li.sku(), cleaned.length()));
            total = total.add(li.price().multiply(BigDecimal.valueOf(li.qty())));
        }
        if (couponCode != null && couponCode.matches("SAVE\\d{2}")) {
            BigDecimal pct = new BigDecimal(couponCode.substring(4));
            total = total.subtract(total.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        }
        return new Quote(customerId, items, total, audit.length());
    }
}
