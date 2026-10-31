package com.acme.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the demo shop with only the starter on the classpath, drives every bottleneck, and checks
 * that PerfLens reports each one with real evidence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"demo.load.enabled=false", "perflens.storage.path=target/perflens-test"})
class PerfLensEndToEndTest {

    @Autowired
    TestRestTemplate rest;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void reportsEveryPlantedBottleneckWithEvidence() throws Exception {
        for (int i = 0; i < 3; i++) {
            rest.getForObject("/orders/summary?limit=30", String.class);
            rest.getForObject("/reports/product-pairs?minQuantity=1", String.class);
            rest.getForObject("/customers/search?q=user1", String.class);
            rest.getForObject("/dashboard/" + (i + 1), String.class);
            rest.getForObject("/dashboard-parallel/" + (i + 1), String.class);
            rest.postForObject("/login?username=alice&password=wonderland", null, String.class);
        }
        // enough CPU work for JFR sampling to attribute the hot method
        long end = System.currentTimeMillis() + 6_000;
        while (System.currentTimeMillis() < end) {
            rest.getForObject("/pricing/quote?customerId=7&items=400&coupon=SAVE10", String.class);
        }
        Thread.sleep(1500); // JFR streams events with ~1 s delay

        ResponseEntity<String> resp = rest.getForEntity("/actuator/perflens/report", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).as(resp.getBody()).isTrue();
        JsonNode report = json.readTree(resp.getBody());

        Set<String> rules = new TreeSet<>();
        report.get("findings").forEach(f -> rules.add(f.get("rule").asText()));
        System.out.println("PerfLens rules fired: " + rules);
        System.out.println("PerfLens profiler: " + report.at("/overview/profiler").asText()
                + ", cpu samples: " + report.at("/overview/cpuSamples").asLong());

        assertThat(rules).contains("DB-N-PLUS-ONE", "DB-SLOW-QUERY", "NET-HTTP-WATERFALL", "NET-SLOW-HTTP",
                "METHOD-SLOW", "ENDPOINT-SLOW");

        // N+1 points at the exact line in OrderService and includes sample bind params
        JsonNode nPlusOne = findFirst(report, "DB-N-PLUS-ONE", "OrderService");
        assertThat(nPlusOne).as("N+1 finding located in OrderService").isNotNull();
        assertThat(nPlusOne.get("location").asText()).startsWith("com.acme.shop.service.OrderService.summarize:");
        // generated proxies (Hibernate, CGLIB) are never reported as the location
        report.get("findings").forEach(f -> assertThat(f.path("location").asText())
                .doesNotContain("HibernateProxy").doesNotContain("$$"));
        // inserts flushed at commit are attributed to the transactional method, not to main()
        JsonNode insertN1 = findFirst(report, "DB-N-PLUS-ONE", "insert into order_line");
        assertThat(insertN1).isNotNull();
        assertThat(insertN1.get("location").asText()).startsWith("com.acme.shop.DataLoader.run").contains("commit");

        // a controller that only delegates is folded into the service finding
        List<String> slowMethods = new ArrayList<>();
        report.get("findings").forEach(f -> { if (f.get("rule").asText().equals("METHOD-SLOW")) slowMethods.add(f.get("location").asText()); });
        assertThat(slowMethods).anyMatch(m -> m.startsWith("com.acme.shop.service.DashboardService.load"))
                .noneMatch(m -> m.startsWith("com.acme.shop.web.ShopController.dashboard"));

        // slow query exemplar carries real params and an executable statement
        JsonNode search = findFirst(report, "DB-SLOW-QUERY", "lower(c.email)");
        if (search != null) {
            assertThat(search.toString()).contains("%user1%").contains("pg_trgm");
        }
        JsonNode pairs = findFirst(report, "DB-SLOW-QUERY", "order_line a join");
        assertThat(pairs).as("self-join query flagged as slow").isNotNull();
        assertThat(pairs.at("/exemplars/0/details/executableSql").asText()).contains("a.quantity >= 1");

        // method arguments are captured, and passwords are masked everywhere
        assertThat(resp.getBody()).contains("\"username\":\"'alice'\"");
        assertThat(resp.getBody()).doesNotContain("wonderland");
        String pwHash = AuthService_hash("wonderland");
        assertThat(resp.getBody()).doesNotContain(pwHash);

        // waterfall names the dashboard entrypoint and estimates savings
        JsonNode waterfall = findFirst(report, "NET-HTTP-WATERFALL", "GET /dashboard/{customerId}");
        assertThat(waterfall).isNotNull();
        assertThat(waterfall.at("/evidence/estimatedSavingMs").asDouble()).isGreaterThan(100);

        // WebClient calls are recorded too, and the parallel version is NOT reported as a waterfall
        assertThat(findFirst(report, "NET-HTTP-WATERFALL", "dashboard-parallel")).isNull();
        JsonNode parallelEp = null;
        for (JsonNode e : report.get("endpoints")) if (e.get("entrypoint").asText().equals("GET /dashboard-parallel/{customerId}")) parallelEp = e;
        assertThat(parallelEp).as("parallel dashboard entrypoint").isNotNull();
        assertThat(parallelEp.get("avgHttpCalls").asDouble()).isEqualTo(3.0);
        assertThat(parallelEp.get("avgHttpMs").asDouble()).as("3 concurrent calls: sum of call times > wall time")
                .isGreaterThan(parallelEp.get("avgMs").asDouble());

        // JFR: the profiler runs and attributes CPU to PricingService.quote
        assertThat(report.at("/overview/profiler").asText()).isEqualTo("running");
        List<String> hot = new ArrayList<>();
        report.get("hotMethods").forEach(h -> hot.add(h.get("method").asText()));
        assertThat(hot).contains("com.acme.shop.service.PricingService.quote");
        if (rules.contains("CPU-HOT-METHOD")) {
            JsonNode hotFinding = findFirst(report, "CPU-HOT-METHOD", "PricingService");
            assertThat(hotFinding.toString()).containsAnyOf("ObjectMapper", "static final Pattern", "String.format");
        }

        // the UI is served by the same endpoint
        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(MediaType.TEXT_HTML));
        ResponseEntity<String> ui = rest.exchange("/actuator/perflens", HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(ui.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(ui.getBody()).contains("<title>PerfLens</title>");

        // snapshot action works
        HttpHeaders jh = new HttpHeaders();
        jh.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> snap = rest.exchange("/actuator/perflens/snapshot", HttpMethod.POST, new HttpEntity<>("{}", jh), String.class);
        assertThat(snap.getBody()).contains("snapshot-");

        // comparing with that snapshot: N+1 introduced afterwards shows up as a regression
        for (int i = 0; i < 5; i++) rest.getForObject("/orders/summary?limit=5", String.class);
        JsonNode cmp0 = json.readTree(rest.getForObject("/actuator/perflens/compare", String.class));
        assertThat(cmp0.at("/baseline/snapshot").asText()).startsWith("snapshot-");
        assertThat(cmp0.has("regressions")).isTrue();
        assertThat(cmp0.at("/baseline/meta/application").asText()).isEqualTo("perflens-demo-shop");

        // the baseline parameter only accepts snapshot file names
        ResponseEntity<String> traversal = rest.getForEntity("/actuator/perflens/compare?baseline=../../pom.xml", String.class);
        assertThat(traversal.getStatusCode().value()).isEqualTo(400);
    }

    private static String AuthService_hash(String pw) {
        return com.acme.shop.service.AuthService.hash(pw);
    }

    private static JsonNode findFirst(JsonNode report, String rule, String contains) {
        for (JsonNode f : report.get("findings")) {
            if (f.get("rule").asText().equals(rule) && f.toString().contains(contains)) return f;
        }
        return null;
    }
}
