package com.acme.loadgen;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sends a steady mix of requests to the demo endpoints so PerfLens has something to show.
 * Lives outside the com.acme.shop package on purpose: it is not "application code" to PerfLens.
 * Disable with demo.load.enabled=false.
 */
public class LoadGenerator {
    private static final Log log = LogFactory.getLog(LoadGenerator.class);

    private final Environment env;

    public LoadGenerator(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!env.getProperty("demo.load.enabled", Boolean.class, true)) return;
        String base = "http://localhost:" + env.getProperty("local.server.port", "8080");
        Thread t = new Thread(() -> loop(base), "demo-load-generator");
        t.setDaemon(true);
        t.start();
        log.info("Demo load generator started against " + base);
    }

    private void loop(String base) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        List<String> gets = List.of("/orders/summary?limit=40", "/reports/product-pairs?minQuantity=1",
                "/customers/search?q=user1", "/pricing/quote?customerId=%d&items=1500&coupon=SAVE10",
                "/dashboard/%d", "/orders/summary?limit=25");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                for (String g : gets) {
                    String path = g.contains("%d") ? String.format(g, ThreadLocalRandom.current().nextInt(1, 200)) : g;
                    http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.discarding());
                }
                http.send(HttpRequest.newBuilder(URI.create(base + "/login?username=alice&password=wonderland"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
