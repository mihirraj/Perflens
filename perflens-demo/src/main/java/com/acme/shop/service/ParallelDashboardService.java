package com.acme.shop.service;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The FIXED version of {@link DashboardService}: the three independent calls run concurrently
 * with WebClient, so the request takes about as long as the slowest call instead of the sum.
 * PerfLens records these calls but does not report a waterfall here.
 */
@Service
public class ParallelDashboardService {
    private final WebClient.Builder builder;
    private final Environment env;
    private volatile WebClient client;

    public ParallelDashboardService(WebClient.Builder builder, Environment env) {
        this.builder = builder;
        this.env = env;
    }

    private WebClient client() {
        WebClient c = client;
        if (c == null) {
            c = builder.baseUrl("http://localhost:" + env.getProperty("local.server.port", "8080")).build();
            client = c;
        }
        return c;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> load(Long customerId) {
        Mono<Map> credit = client().get().uri("/mock/credit/{id}", customerId).retrieve().bodyToMono(Map.class);
        Mono<Map> shipping = client().get().uri("/mock/shipping/{id}", customerId).retrieve().bodyToMono(Map.class);
        Mono<Map> loyalty = client().get().uri("/mock/loyalty/{id}", customerId).retrieve().bodyToMono(Map.class)
                .onErrorReturn(Map.of("available", false));
        return Mono.zip(credit, shipping, loyalty).map(t -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("credit", t.getT1());
            out.put("shipping", t.getT2());
            out.put("loyalty", t.getT3());
            return out;
        }).block();
    }
}
