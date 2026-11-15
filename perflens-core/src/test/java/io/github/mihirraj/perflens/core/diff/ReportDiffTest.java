package io.github.mihirraj.perflens.core.diff;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportDiffTest {

    private static Map<String, Object> endpoint(String name, double count, double avgMs, double sqlPerReq) {
        return Map.of("entrypoint", name, "count", count, "avgMs", avgMs, "p95Ms", avgMs * 1.5,
                "totalMs", count * avgMs, "avgSqlQueries", sqlPerReq, "avgHttpCalls", 0.0);
    }

    private static Map<String, Object> report(List<Map<String, Object>> endpoints, List<Map<String, Object>> findings) {
        return Map.of("endpoints", endpoints, "findings", findings);
    }

    @Test
    void nPlusOneRegressionIsRankedAboveNoiseAndTrafficGrowthIsIgnored() {
        Map<String, Object> before = report(List.of(
                endpoint("GET /orders", 100, 40, 2),
                endpoint("GET /health-ish", 100, 1.0, 0),
                endpoint("GET /busy", 100, 50, 1)), List.of());
        Map<String, Object> after = report(List.of(
                endpoint("GET /orders", 100, 180, 42),     // N+1 introduced
                endpoint("GET /health-ish", 100, 1.6, 0),  // +60% but tiny: below min delta
                endpoint("GET /busy", 5000, 50, 1)),       // 50x traffic, same per-request cost
                List.of());

        Map<String, Object> d = ReportDiff.diff(before, after, 0.25, 1.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> regs = (List<Map<String, Object>>) d.get("regressions");

        assertThat(regs).isNotEmpty();
        assertThat(regs).allMatch(r -> r.get("item").equals("GET /orders"));
        assertThat(regs.get(0).get("metric")).isEqualTo("SQL queries per request");
        assertThat(regs).anyMatch(r -> r.get("metric").equals("avg time (ms)") && (Double) r.get("changePercent") == 350.0);
    }

    @Test
    void improvementsAndFindingChangesAreReported() {
        Map<String, Object> f1 = Map.of("id", "DB-N-PLUS-ONE-1", "rule", "DB-N-PLUS-ONE", "title", "old");
        Map<String, Object> f2 = Map.of("id", "NET-SLOW-HTTP-2", "rule", "NET-SLOW-HTTP", "title", "new");
        Map<String, Object> before = report(List.of(endpoint("GET /a", 10, 300, 30)), List.of(f1));
        Map<String, Object> after = report(List.of(endpoint("GET /a", 10, 60, 3)), List.of(f2));

        Map<String, Object> d = ReportDiff.diff(before, after, 0.25, 1.0);
        assertThat((List<?>) d.get("regressions")).isEmpty();
        assertThat((List<?>) d.get("improvements")).isNotEmpty();
        assertThat(d.get("newFindings").toString()).contains("NET-SLOW-HTTP-2");
        assertThat(d.get("resolvedFindings").toString()).contains("DB-N-PLUS-ONE-1");
    }

    @Test
    void newEndpointsAreListed() {
        Map<String, Object> d = ReportDiff.diff(report(List.of(), List.of()),
                report(List.of(endpoint("POST /checkout", 20, 250, 5)), List.of()), 0.25, 1.0);
        assertThat(d.get("newEndpoints").toString()).contains("POST /checkout");
    }
}
