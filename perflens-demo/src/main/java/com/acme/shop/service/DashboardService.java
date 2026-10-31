package com.acme.shop.service;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DashboardService {
    private final RestClient.Builder builder;
    private final Environment env;
    private volatile RestClient client;

    public DashboardService(RestClient.Builder builder, Environment env) {
        this.builder = builder;
        this.env = env;
    }

    private RestClient client() {
        RestClient c = client;
        if (c == null) {
            c = builder.baseUrl("http://localhost:" + env.getProperty("local.server.port", "8080")).build();
            client = c;
        }
        return c;
    }

    /**
     * BOTTLENECK: three independent downstream calls made one after another (waterfall); the
     * credit service is slow and the loyalty service fails intermittently.
     */
    public Map<String, Object> load(Long customerId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("credit", client().get().uri("/mock/credit/{id}", customerId).retrieve().body(Map.class));
        out.put("shipping", client().get().uri("/mock/shipping/{id}", customerId).retrieve().body(Map.class));
        try {
            out.put("loyalty", client().get().uri("/mock/loyalty/{id}", customerId).retrieve().body(Map.class));
        } catch (RestClientException e) {
            out.put("loyalty", Map.of("available", false));
        }
        return out;
    }
}
