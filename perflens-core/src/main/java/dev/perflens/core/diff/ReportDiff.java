package dev.perflens.core.diff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares two reports (a baseline snapshot and the current state, both as plain JSON maps) and
 * ranks what got worse. Only traffic-independent metrics are compared (averages, percentiles,
 * queries per request, CPU share, error rate), so a busier day is not a regression by itself.
 *
 * <p>Score, from the design doc: {@code S = (after - before) / max(before, eps) * ln(1 + impact)},
 * where impact is the time (ms) the item consumed in the current period. A big relative change on
 * something that barely runs therefore ranks low.
 */
public final class ReportDiff {

    /** One comparable metric of one report section. */
    record Metric(String section, String keyField, String metric, String label, double minAbsDelta,
                  String volumeField, double minVolume, String impactField) {}

    static final List<Metric> METRICS = List.of(
            new Metric("queries", "sql", "avgMs", "avg time (ms)", 2, "count", 3, "totalMs"),
            new Metric("queries", "sql", "p95Ms", "p95 (ms)", 5, "count", 3, "totalMs"),
            new Metric("methods", "method", "avgMs", "avg time (ms)", 5, "count", 3, "totalMs"),
            new Metric("methods", "method", "p95Ms", "p95 (ms)", 10, "count", 3, "totalMs"),
            new Metric("endpoints", "entrypoint", "avgMs", "avg time (ms)", 10, "count", 3, "totalMs"),
            new Metric("endpoints", "entrypoint", "p95Ms", "p95 (ms)", 20, "count", 3, "totalMs"),
            new Metric("endpoints", "entrypoint", "avgSqlQueries", "SQL queries per request", 1, "count", 3, "totalMs"),
            new Metric("endpoints", "entrypoint", "avgHttpCalls", "outbound calls per request", 1, "count", 3, "totalMs"),
            new Metric("http.routes", "route", "avgMs", "avg time (ms)", 10, "count", 3, "totalMs"),
            new Metric("http.routes", "route", "p95Ms", "p95 (ms)", 20, "count", 3, "totalMs"),
            new Metric("http.routes", "route", "errorRatePercent", "error rate (%)", 2, "count", 5, "totalMs"),
            new Metric("hotMethods", "method", "cpuPercent", "share of CPU (%)", 2, "samples", 10, "samples"));

    private static final double EPS = 0.5;

    private ReportDiff() {}

    /**
     * @param minRelativeChange e.g. 0.25 = only changes of 25% or more are reported
     * @param tau               minimum regression score
     */
    public static Map<String, Object> diff(Map<String, Object> baseline, Map<String, Object> current,
                                           double minRelativeChange, double tau) {
        List<Map<String, Object>> regressions = new ArrayList<>();
        List<Map<String, Object>> improvements = new ArrayList<>();
        for (Metric m : METRICS) {
            Map<String, Map<String, Object>> before = index(rows(baseline, m.section()), m.keyField());
            Map<String, Map<String, Object>> after = index(rows(current, m.section()), m.keyField());
            for (Map.Entry<String, Map<String, Object>> e : after.entrySet()) {
                Map<String, Object> b = before.get(e.getKey());
                Map<String, Object> a = e.getValue();
                if (b == null || num(a, m.volumeField()) < m.minVolume() || num(b, m.volumeField()) < m.minVolume()) continue;
                double vb = value(b, m.metric());
                double va = value(a, m.metric());
                double delta = va - vb;
                double rel = delta / Math.max(Math.abs(vb), EPS);
                if (Math.abs(delta) < m.minAbsDelta() || Math.abs(rel) < minRelativeChange) continue;
                double impact = num(a, m.impactField());
                double score = rel * Math.log1p(Math.max(0, impact));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("area", m.section());
                row.put("item", e.getKey());
                row.put("metric", m.label());
                row.put("before", round(vb));
                row.put("after", round(va));
                row.put("changePercent", round(rel * 100));
                row.put("score", round(score));
                if (delta > 0 && score >= tau) regressions.add(row);
                else if (delta < 0) improvements.add(row);
            }
        }
        regressions.sort(Comparator.comparingDouble((Map<String, Object> r) -> (Double) r.get("score")).reversed());
        improvements.sort(Comparator.comparingDouble((Map<String, Object> r) -> (Double) r.get("score")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("regressions", regressions);
        out.put("improvements", improvements);
        out.putAll(findingChanges(baseline, current));
        out.put("newQueries", newItems(baseline, current, "queries", "sql"));
        out.put("newEndpoints", newItems(baseline, current, "endpoints", "entrypoint"));
        return out;
    }

    private static Map<String, Object> findingChanges(Map<String, Object> baseline, Map<String, Object> current) {
        Map<String, Map<String, Object>> before = index(rows(baseline, "findings"), "id");
        Map<String, Map<String, Object>> after = index(rows(current, "findings"), "id");
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> resolved = new ArrayList<>();
        after.forEach((id, f) -> { if (!before.containsKey(id)) added.add(brief(f)); });
        before.forEach((id, f) -> { if (!after.containsKey(id)) resolved.add(brief(f)); });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("newFindings", added);
        m.put("resolvedFindings", resolved);
        return m;
    }

    private static Map<String, Object> brief(Map<String, Object> f) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("id", "rule", "severity", "score", "title", "location")) m.put(k, f.get(k));
        return m;
    }

    /** Items that did not exist in the baseline, biggest time consumers first. */
    private static List<Map<String, Object>> newItems(Map<String, Object> baseline, Map<String, Object> current,
                                                      String section, String keyField) {
        Map<String, Map<String, Object>> before = index(rows(baseline, section), keyField);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows(current, section)) {
            Object key = r.get(keyField);
            if (key == null || before.containsKey(String.valueOf(key))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", key);
            m.put("count", r.get("count"));
            m.put("totalMs", r.get("totalMs"));
            m.put("avgMs", r.get("avgMs"));
            out.add(m);
        }
        out.sort(Comparator.comparingDouble((Map<String, Object> m) -> num(m, "totalMs")).reversed());
        return out.size() > 20 ? new ArrayList<>(out.subList(0, 20)) : out;
    }

    // ---- JSON map helpers ----

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> report, String path) {
        Object node = report;
        for (String part : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) return List.of();
            node = map.get(part);
        }
        if (!(node instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    private static Map<String, Map<String, Object>> index(List<Map<String, Object>> rows, String keyField) {
        Map<String, Map<String, Object>> m = new HashMap<>();
        for (Map<String, Object> r : rows) {
            Object k = r.get(keyField);
            if (k != null) m.putIfAbsent(String.valueOf(k), r);
        }
        return m;
    }

    /** Metric value; error rate is derived when the report doesn't carry it directly. */
    private static double value(Map<String, Object> row, String metric) {
        if (metric.equals("errorRatePercent") && !row.containsKey(metric)) {
            double count = num(row, "count");
            return count == 0 ? 0 : num(row, "errors") * 100.0 / count;
        }
        return num(row, metric);
    }

    private static double num(Map<String, Object> row, String field) {
        Object v = row.get(field);
        if (v instanceof Number n) return n.doubleValue();
        if (v != null) {
            try {
                return Double.parseDouble(Objects.toString(v));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
