package it.reactiveapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PerfLens on a WebFlux + R2DBC application: SQL with params, route timing, async methods skipped. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = ReactiveApp.class, properties = {
        "spring.r2dbc.url=r2dbc:h2:mem:///perflensit;DB_CLOSE_DELAY=-1",
        "spring.sql.init.schema-locations=classpath:reactive-schema.sql",
        "perflens.sql.slow-query-threshold=1ms",
        "perflens.methods.exemplar-min-duration=0ms",
        "perflens.storage.enabled=false",
        "perflens.jfr.enabled=false"})
class ReactiveR2dbcIntegrationTest {

    @Autowired
    WebTestClient web;

    @Test
    void recordsR2dbcQueriesWithParamsAndReactiveRoutes() throws Exception {
        WebTestClient client = web.mutate().responseTimeout(Duration.ofSeconds(30)).build();
        for (int i = 0; i < 5; i++) {
            client.get().uri("/items/{id}", 2).exchange().expectStatus().isOk();
            client.get().uri("/count?mod={m}", 7).exchange().expectStatus().isOk();
        }

        byte[] body = client.get().uri("/actuator/perflens/report").exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody();
        JsonNode report = new ObjectMapper().readTree(body);

        // R2DBC statements are recorded, with real parameters and an executable statement
        JsonNode heavy = null;
        for (JsonNode q : report.get("queries")) if (q.get("sql").asText().contains("SYSTEM_RANGE")) heavy = q;
        assertThat(heavy).as("R2DBC query recorded; queries=%s", report.get("queries")).isNotNull();
        assertThat(heavy.get("count").asLong()).isEqualTo(5);
        assertThat(heavy.at("/slowest/0/details/params/0").asText()).isEqualTo("7");
        assertThat(heavy.at("/slowest/0/details/executableSql").asText()).contains("MOD(X, 7)");

        // routes are timed by the WebFilter, named by their template
        List<String> entrypoints = new ArrayList<>();
        report.get("endpoints").forEach(e -> entrypoints.add(e.get("entrypoint").asText()));
        assertThat(entrypoints).contains("GET /items/{id}", "GET /count");

        // Mono-returning methods are skipped by the method advisor; synchronous ones are timed
        List<String> methods = new ArrayList<>();
        report.get("methods").forEach(m -> methods.add(m.get("key").asText()));
        assertThat(methods).contains("it.reactiveapp.ReactiveApp$ItemService.label")
                .doesNotContain("it.reactiveapp.ReactiveApp$ItemService.find", "it.reactiveapp.ReactiveApp$ItemService.heavyCount",
                        "it.reactiveapp.ReactiveApp$ItemController.item");

        // and the slow-query rule works on R2DBC evidence
        List<String> rules = new ArrayList<>();
        report.get("findings").forEach(f -> rules.add(f.get("rule").asText()));
        assertThat(rules).contains("DB-SLOW-QUERY");
    }

    @Test
    void attributesR2dbcQueriesAndWebClientCallsToTheirWebFluxRequest() throws Exception {
        WebTestClient client = web.mutate().responseTimeout(Duration.ofSeconds(30)).build();
        for (int i = 0; i < 3; i++) {
            client.get().uri("/items/n-plus-one").exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("12");
            client.get().uri("/items/chain").exchange().expectStatus().isOk();
        }

        byte[] body = client.get().uri("/actuator/perflens/report").exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody();
        JsonNode report = new ObjectMapper().readTree(body);

        // the 12 queries, completed concurrently on several threads, all count towards their request
        JsonNode nPlusOne = endpoint(report, "GET /items/n-plus-one");
        assertThat(nPlusOne.get("avgSqlQueries").asDouble()).isEqualTo(12.0);

        JsonNode finding = null;
        for (JsonNode f : report.get("findings")) {
            if (f.get("rule").asText().equals("DB-N-PLUS-ONE")
                    && f.at("/evidence/entrypoint").asText().equals("GET /items/n-plus-one")) finding = f;
        }
        assertThat(finding).as("reactive N+1 finding; findings=%s", report.get("findings")).isNotNull();
        assertThat(finding.at("/evidence/maxPerRequest").asLong()).isEqualTo(12);
        assertThat(finding.at("/evidence/sql").asText()).contains("FROM ITEM WHERE ID = $1");

        // WebClient calls are attributed to the WebFlux request that made them
        JsonNode chain = endpoint(report, "GET /items/chain");
        assertThat(chain.get("avgHttpCalls").asDouble()).isEqualTo(3.0);
        // and the downstream requests' own queries stay with the downstream route
        assertThat(chain.get("avgSqlQueries").asDouble()).isEqualTo(0.0);
    }

    private static JsonNode endpoint(JsonNode report, String entrypoint) {
        for (JsonNode e : report.get("endpoints")) if (e.get("entrypoint").asText().equals(entrypoint)) return e;
        throw new AssertionError(entrypoint + " not in " + report.get("endpoints"));
    }
}
