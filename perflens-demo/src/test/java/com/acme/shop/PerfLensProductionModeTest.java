package com.acme.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests from the 2026-11-16 test report: with a production profile active no request
 * values reach the report, outbound URLs built by concatenation collapse into one route, and a
 * flood of unknown URLs cannot push real endpoints out of the endpoint table.
 */
@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "demo.load.enabled=false", "perflens.storage.enabled=false", "perflens.jfr.enabled=false",
        // REST-only apps: unknown URLs match no handler at all (no /** static-resource fallback)
        "spring.web.resources.add-mappings=false"})
class PerfLensProductionModeTest {
    /** Distinctive values that must never appear in the report. */
    private static final long CUSTOMER_ID = 987_654_321L;
    private static final long FIRST_ITEM_ID = 555_000_100L;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    RestClient.Builder restClient;

    @LocalServerPort
    int port;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void recordsNoRequestValuesInProductionMode() throws Exception {
        rest.getForObject("/dashboard/" + CUSTOMER_ID, String.class);
        // a client that builds URLs by concatenation, as many do
        RestClient client = restClient.baseUrl("http://localhost:" + port).build();
        for (int i = 0; i < 25; i++) {
            client.get().uri("/mock/shipping/" + (FIRST_ITEM_ID + i)).retrieve().toBodilessEntity();
        }

        String body = rest.getForObject("/actuator/perflens/report", String.class);
        JsonNode report = json.readTree(body);
        assertThat(report.at("/overview/captureParams").asBoolean()).isFalse();

        assertThat(body).doesNotContain(String.valueOf(CUSTOMER_ID)).doesNotContain(String.valueOf(FIRST_ITEM_ID))
                .doesNotContain("executableSql");
        assertThat(entrypoints(report)).contains("GET /dashboard/{customerId}", "GET /mock/shipping/{id}");

        // route keys are "METHOD host/route"
        List<String> routes = new ArrayList<>();
        report.at("/http/routes").forEach(r -> routes.add(r.get("route").asText()));
        assertThat(routes).anyMatch(r -> r.endsWith("/mock/shipping/{id}")).noneMatch(r -> r.matches(".*/\\d+$"));
    }

    @Test
    void unknownUrlsShareOneEntryAndCannotCrowdOutRealEndpoints() throws Exception {
        for (int i = 0; i < 600; i++) rest.getForEntity("/wp-admin/probe-" + i + ".php", String.class);
        rest.getForObject("/orders/summary?limit=5", String.class);

        JsonNode report = json.readTree(rest.getForObject("/actuator/perflens/report", String.class));
        List<String> entrypoints = entrypoints(report);
        assertThat(entrypoints).contains("GET [unmapped]", "GET /orders/summary").noneMatch(e -> e.contains("probe"));
    }

    private static List<String> entrypoints(JsonNode report) {
        List<String> out = new ArrayList<>();
        report.get("endpoints").forEach(e -> out.add(e.get("entrypoint").asText()));
        return out;
    }
}
